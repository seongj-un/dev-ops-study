package com.devopsstudy.shortener.shorturl

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * 조회수 쓰기를 요청 스레드 밖으로 뺀 ClickRecorder를 컨테이너 없이 확인한다.
 * 실행기는 대부분 ManualExecutor(테스트가 runPending()을 부를 때만 작업이 돈다)를 넣어 작업이 언제 도는지를 테스트가 정하고,
 * 대기열이 차는 동작은 실제로 쓰는 실행기(ClickRecorder.boundedExecutor)로도 확인한다. 시간은 TestTimeSource로 움직인다.
 */
class ClickRecorderTest {
	private val meterRegistry = SimpleMeterRegistry()
	private val time = TestTimeSource()
	private val written = ConcurrentLinkedQueue<String>()
	private val logs = LogCapture(ClickRecorder::class.java)

	@AfterEach
	fun tearDown() {
		logs.close()
	}

	private fun recorder(
		executor: ExecutorService,
		shutdownTimeout: Duration = Duration.ofSeconds(5),
		write: (String) -> Unit = { written += it },
	) = ClickRecorder(executor, write, meterRegistry, shutdownTimeout, time)

	private fun recorded() = meterRegistry.counter(ClickRecorder.RECORDED).count()

	private fun dropped(reason: String) = meterRegistry.counter(ClickRecorder.DROPPED, "reason", reason).count()

	private val dbDown: (String) -> Unit = { throw CannotCreateTransactionException("Could not open JPA EntityManager for transaction") }

	@Test
	fun `record는 DB에 쓰지 않고 대기열에 넣기만 한 뒤 돌아온다`() {
		val executor = ManualExecutor()
		val recorder = recorder(executor)

		recorder.record("abc1234")

		assertTrue(written.isEmpty(), "요청 스레드에서는 쓰지 않는다")
		assertEquals(1, executor.pendingCount)

		executor.runPending()

		assertEquals(listOf("abc1234"), written.toList())
		assertEquals(1.0, recorded())
	}

	@Test
	fun `카운터는 처음부터 0으로 등록돼 있다`() {
		recorder(ManualExecutor())

		assertEquals(0.0, recorded())
		listOf("queue_full", "error", "shutdown").forEach { assertEquals(0.0, dropped(it), it) }
	}

	@Test
	fun `DB 쓰기가 실패해도 예외를 밖으로 던지지 않고 error로 센다`() {
		// 커넥션을 못 받으면 트랜잭션을 열다 실패해서 DataAccessException이 아닌 TransactionException이 난다. 이것도 잡아야 한다
		val executor = ManualExecutor()
		val recorder = recorder(executor, write = dbDown)

		repeat(3) { recorder.record("abc1234") }
		executor.runPending()

		assertEquals(0.0, recorded())
		assertEquals(3.0, dropped("error"))
	}

	@Test
	fun `DB 쓰기 실패 경고는 10초에 한 줄이고 그사이 거른 수를 suppressed로 남긴다`() {
		val executor = ManualExecutor()
		val recorder = recorder(executor, write = dbDown)

		repeat(3) { recorder.record("abc1234") }
		executor.runPending()
		time += 10.seconds
		recorder.record("xyz9876")
		executor.runPending()

		val warnings = logs.warnings()
		assertEquals(2, warnings.size)
		assertEquals("click count write failed, dropping", warnings[0].formattedMessage)
		assertEquals(0L, warnings[0].fields()["suppressed"])
		assertEquals("abc1234", warnings[0].fields()["code"])
		assertEquals(2L, warnings[1].fields()["suppressed"])
		assertTrue(warnings.none { it.throwableProxy != null }, "스택트레이스는 남기지 않는다")
	}

	@Test
	fun `대기열이 차면 버리고 queue_full로 센다`() {
		val executor = ManualExecutor(capacity = 2)
		val recorder = recorder(executor)

		repeat(5) { recorder.record("abc1234") }
		executor.runPending()

		assertEquals(2.0, recorded())
		assertEquals(3.0, dropped("queue_full"))
		val warning = logs.warnings().single()
		assertEquals("click count dropped", warning.formattedMessage)
		assertEquals("queue_full", warning.fields()["reason"])
	}

	@Test
	fun `실제 실행기도 대기열이 차면 기다리지 않고 버린다`() {
		// 스레드 1개, 대기열 1칸: 첫 쓰기가 스레드를 붙잡고 있는 동안 두 번째는 대기열에 들어가고 세 번째는 자리가 없다
		val release = CountDownLatch(1)
		val recorder = recorder(ClickRecorder.boundedExecutor(threads = 1, queueCapacity = 1)) { code ->
			release.await(5, TimeUnit.SECONDS)
			written += code
		}

		recorder.record("first01")
		recorder.record("second2")
		recorder.record("third03")

		assertEquals(1.0, dropped("queue_full"))
		release.countDown()
		recorder.close()
		assertEquals(listOf("first01", "second2"), written.toList())
		assertEquals(2.0, recorded())
		assertEquals(0.0, dropped("shutdown"))
	}

	@Test
	fun `실제 실행기의 스레드 이름은 click-recorder로 시작한다`() {
		var threadName = ""
		val recorder = recorder(ClickRecorder.boundedExecutor(threads = 1, queueCapacity = 1)) { threadName = Thread.currentThread().name }

		recorder.record("abc1234")
		recorder.close()

		assertTrue(threadName.startsWith("click-recorder-"), threadName)
	}

	@Test
	fun `종료할 때 대기열에 남은 쓰기를 마저 끝낸다`() {
		val recorder = recorder(ClickRecorder.boundedExecutor(threads = 2, queueCapacity = 100))

		repeat(50) { recorder.record("code$it") }
		recorder.close()

		assertEquals(50, written.size)
		assertEquals(50.0, recorded())
		assertEquals(0.0, dropped("shutdown"))
		assertTrue(logs.warnings().isEmpty())
	}

	@Test
	fun `종료 대기 시간 안에 못 쓴 것은 버리고 shutdown으로 센다`() {
		// ManualExecutor는 아무도 작업을 돌리지 않으므로 기다려도 끝나지 않는다: 대기 시간이 지난 상황과 같다
		val executor = ManualExecutor()
		val recorder = recorder(executor, shutdownTimeout = Duration.ofSeconds(3))
		repeat(4) { recorder.record("abc1234") }

		recorder.close()

		assertEquals(0, executor.pendingCount)
		assertEquals(4.0, dropped("shutdown"))
		val warning = logs.warnings().single()
		assertEquals("click recorder stopped before writing all click counts", warning.formattedMessage)
		assertEquals(4, warning.fields()["dropped"])
	}

	@Test
	fun `종료한 뒤에 들어온 조회수는 shutdown으로 센다`() {
		val recorder = recorder(ManualExecutor())
		recorder.close()

		recorder.record("abc1234")

		assertEquals(1.0, dropped("shutdown"))
		assertEquals(0.0, dropped("queue_full"))
	}
}
