package com.devopsstudy.shortener.shorturl

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/**
 * 리다이렉트의 조회수 +1을 요청 스레드가 아니라 따로 둔 스레드에서 DB에 쓴다.
 * 조회수는 리다이렉트에 꼭 필요한 일이 아니라서, DB가 느리거나 죽어도 302 응답을 기다리게 하거나 실패시키면 안 된다.
 * 5단계 장애 훈련에서는 조회수를 요청 안에서 써서, 캐시에 있어 DB 없이 답할 수 있던 리다이렉트까지 DB 장애로 실패했다.
 *
 * 대신 조회수를 잃을 수 있다. 잃은 수는 shortener.clicks.dropped의 reason으로 나눠 센다 (쓴 수는 shortener.clicks.recorded).
 * - queue_full: 대기열이 차서 받지 못했다. DB가 느려 쓰기가 밀리면 생긴다.
 * - error: DB 쓰기가 실패했다. 다시 시도하지 않는다 (DB 장애 중에 다시 시도하면 대기열만 더 밀린다).
 * - shutdown: 앱이 종료될 때 shutdownTimeout 안에 다 쓰지 못했다 (close 참고).
 * 프로세스가 강제로 죽으면(SIGKILL, OOM) 대기열에 있던 조회수는 세지도 못하고 사라진다.
 * 그래서 shortener.redirects = recorded + dropped + 대기열 + 쓰는 중이고, 강제 종료가 없었다면 대기열이 비고 쓰는 중인 것이 끝난 뒤에는 앞의 둘의 합과 같다.
 */
class ClickRecorder(
	private val executor: ExecutorService,
	private val write: (code: String) -> Unit,
	meterRegistry: MeterRegistry,
	private val shutdownTimeout: Duration,
	timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {
	private val log = LoggerFactory.getLogger(javaClass)

	// 장애가 나기 전에도 0으로 보이게 미리 등록한다. 그래야 대시보드와 쿼리가 "데이터 없음"이 되지 않는다
	private val recorded = meterRegistry.counter(RECORDED)
	private val droppedQueueFull = meterRegistry.counter(DROPPED, "reason", "queue_full")
	private val droppedError = meterRegistry.counter(DROPPED, "reason", "error")
	private val droppedShutdown = meterRegistry.counter(DROPPED, "reason", "shutdown")

	// DB 장애 중에는 실패가 리다이렉트마다 나므로 경고는 종류마다 LOG_WINDOW에 한 줄만 남긴다
	private val writeFailureWarnings = LogThrottle(LOG_WINDOW, timeSource)
	private val rejectionWarnings = LogThrottle(LOG_WINDOW, timeSource)

	/** 조회수 +1을 대기열에 넣고 바로 돌아온다. 예외를 던지지 않는다. */
	fun record(code: String) {
		try {
			executor.execute { writeOne(code) }
		} catch (e: RejectedExecutionException) {
			val reason = if (executor.isShutdown) {
				droppedShutdown.increment()
				"shutdown"
			} else {
				droppedQueueFull.increment()
				"queue_full"
			}
			val suppressed = rejectionWarnings.acquire() ?: return
			log.atWarn().addKeyValue("code", code).addKeyValue("reason", reason).addKeyValue("suppressed", suppressed)
				.log("click count dropped")
		}
	}

	private fun writeOne(code: String) {
		try {
			write(code)
			recorded.increment()
		} catch (e: Exception) {
			// DataAccessException만 잡으면 안 된다: 트랜잭션을 열다 커넥션을 못 받으면 CannotCreateTransactionException(TransactionException)이 난다
			droppedError.increment()
			val suppressed = writeFailureWarnings.acquire() ?: return
			log.atWarn().addKeyValue("code", code).addKeyValue("error", e.message).addKeyValue("suppressed", suppressed)
				.log("click count write failed, dropping")
		}
	}

	/**
	 * 앱이 종료될 때 스프링이 부른다 (@Bean의 AutoCloseable.close). 웹 서버가 처리 중인 요청을 다 끝낸 뒤라 더 들어오는 조회수는 없다.
	 * 대기열에 남은 쓰기를 shutdownTimeout까지 기다려 마저 쓰고, 그래도 남은 것은 버리고 reason=shutdown으로 센다.
	 * 이 빈은 리포지토리(→ DataSource)에 기대므로 스프링은 DataSource보다 먼저 이 빈을 닫는다. 그래서 기다리는 동안 DB 커넥션 풀은 살아 있다.
	 */
	override fun close() {
		executor.shutdown()
		val finished = try {
			executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}
		if (finished) return
		// 아직 시작하지 못한 쓰기만 돌려받는다. 이미 쓰고 있던 것은 끝나면 recorded, 실패하면 error로 센다
		val pending = executor.shutdownNow().size
		droppedShutdown.increment(pending.toDouble())
		log.atWarn().addKeyValue("dropped", pending).addKeyValue("timeout", shutdownTimeout.toString())
			.log("click recorder stopped before writing all click counts")
	}

	companion object {
		const val RECORDED = "shortener.clicks.recorded"
		const val DROPPED = "shortener.clicks.dropped"
		val LOG_WINDOW: Duration = Duration.ofSeconds(10)

		/**
		 * 스레드 threads개와 크기가 queueCapacity로 정해진 대기열을 가진 실행기. 대기열이 차면 execute가 RejectedExecutionException을 던진다(AbortPolicy).
		 * Executors.newFixedThreadPool은 대기열 크기에 제한이 없어서, DB 장애가 길면 쌓인 작업이 메모리를 다 먹는다.
		 * 스레드 이름(click-recorder-N)은 스레드 덤프에서 알아보려는 것이다. 데몬 스레드라 혹시 닫지 않고 끝나도 JVM 종료를 막지 않는다.
		 */
		fun boundedExecutor(threads: Int, queueCapacity: Int): ThreadPoolExecutor =
			ThreadPoolExecutor(
				threads,
				threads,
				0L,
				TimeUnit.MILLISECONDS,
				ArrayBlockingQueue(queueCapacity),
				CustomizableThreadFactory("click-recorder-").apply { isDaemon = true },
				ThreadPoolExecutor.AbortPolicy(),
			)
	}
}
