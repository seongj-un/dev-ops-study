package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.TestTimeSource

/**
 * 리다이렉트 경로(ShortUrlService.resolve)가 DB·Redis 장애에서 어떻게 버티는지를 컨테이너 없이 확인한다.
 * DB는 Mockito로 만든 가짜 리포지토리(dbDown을 켜면 모든 호출이 커넥션 실패), Redis는 FakeRedis, 조회수 실행기는 ManualExecutor다.
 * 실제 DB·Redis와 HTTP까지 붙여서 보는 것은 DatabaseOutageTest(Docker 필요)가 한다.
 */
class ShortUrlServiceResolveTest {
	private val meterRegistry = SimpleMeterRegistry()
	private val redis = FakeRedis()
	private val rows = ConcurrentHashMap<String, ShortUrl>()

	@Volatile
	private var dbDown = false
	private var dbCalls = 0

	private val repository = Mockito.mock(
		ShortUrlRepository::class.java,
		Answer { invocation ->
			dbCalls++
			if (dbDown) throw DataAccessResourceFailureException("Unable to acquire JDBC Connection")
			when (invocation.method.name) {
				"findByCode" -> rows[invocation.arguments[0] as String]
				else -> error("가짜 리포지토리가 모르는 호출이다: ${invocation.method}")
			}
		},
	)

	private val clickExecutor = ManualExecutor()
	private val clicksWritten = mutableListOf<String>()
	private val clickRecorder = ClickRecorder(
		executor = clickExecutor,
		write = { code ->
			// 실제 incrementClickCount는 트랜잭션을 열다 커넥션을 못 받으면 CannotCreateTransactionException을 던진다
			if (dbDown) throw CannotCreateTransactionException("Could not open JPA EntityManager for transaction")
			clicksWritten += code
		},
		meterRegistry = meterRegistry,
		shutdownTimeout = Duration.ofSeconds(1),
	)
	private val service = ShortUrlService(
		repository,
		{ error("리다이렉트는 코드를 만들지 않는다") },
		UrlCache(redis.template, ShortenerProperties(), meterRegistry, TestTimeSource()),
		clickRecorder,
		meterRegistry,
	)

	@AfterEach
	fun tearDown() {
		clickRecorder.close()
	}

	private fun saved(code: String, url: String) {
		rows[code] = ShortUrl(code, url)
	}

	private fun cached(code: String, url: String) {
		redis.store["${UrlCache.KEY_PREFIX}$code"] = url
	}

	private fun dropped(reason: String) = meterRegistry.counter(ClickRecorder.DROPPED, "reason", reason).count()

	@Test
	fun `캐시에 있으면 DB가 죽어도 원본 URL을 돌려주고 잃은 조회수를 센다`() {
		saved("abc1234", "https://example.com/cached")
		cached("abc1234", "https://example.com/cached")
		dbDown = true

		assertEquals("https://example.com/cached", service.resolve("abc1234"))
		assertEquals(0, dbCalls, "응답을 만드는 동안 DB를 부르지 않는다")
		assertEquals(1.0, meterRegistry.counter("shortener.redirects").count())

		// 조회수 쓰기는 응답과 따로 돈다. DB가 죽어 있으니 실패하고 error로 센다
		clickExecutor.runPending()
		assertEquals(1.0, dropped("error"))
		assertEquals(0.0, meterRegistry.counter(ClickRecorder.RECORDED).count())
	}

	@Test
	fun `캐시에 없으면 DB가 죽었을 때 예외가 나서 500이 되고 조회수는 넣지 않는다`() {
		saved("abc1234", "https://example.com/uncached")
		dbDown = true

		assertFailsWith<DataAccessResourceFailureException> { service.resolve("abc1234") }

		assertEquals(0, clickExecutor.pendingCount)
		assertEquals(0.0, meterRegistry.counter("shortener.redirects").count())
	}

	@Test
	fun `캐시에 없으면 DB에서 읽어 캐시에 넣고 조회수는 나중에 쓴다`() {
		saved("abc1234", "https://example.com/miss")

		assertEquals("https://example.com/miss", service.resolve("abc1234"))

		assertEquals("https://example.com/miss", redis.store["${UrlCache.KEY_PREFIX}abc1234"])
		assertEquals(emptyList(), clicksWritten, "응답을 돌려줄 때까지는 쓰지 않았다")
		clickExecutor.runPending()
		assertEquals(listOf("abc1234"), clicksWritten)
		assertEquals(1.0, meterRegistry.counter(ClickRecorder.RECORDED).count())
	}

	@Test
	fun `Redis가 죽으면 한 요청에서 Redis를 한 번만 부르고 그다음 요청부터는 부르지 않는다`() {
		// 5단계 장애 훈련에서는 get과 put이 둘 다 타임아웃을 기다려서 리다이렉트마다 약 1초가 더 걸렸다
		saved("abc1234", "https://example.com/redis-down")
		redis.down = true

		assertEquals("https://example.com/redis-down", service.resolve("abc1234"))
		assertEquals(1, redis.calls.get(), "get이 실패한 뒤 put은 건너뛴다")

		assertEquals("https://example.com/redis-down", service.resolve("abc1234"))
		assertEquals(1, redis.calls.get(), "창 안의 요청은 Redis를 부르지 않는다")
		assertEquals(1.0, meterRegistry.counter(UrlCache.METRIC, "result", "error").count())
		assertEquals(1.0, meterRegistry.counter(UrlCache.METRIC, "result", "skipped").count())
	}

	@Test
	fun `없는 코드는 404 예외이고 조회수를 넣지 않는다`() {
		assertFailsWith<ShortUrlNotFoundException> { service.resolve("nosuch1") }

		assertEquals(0, clickExecutor.pendingCount)
	}
}
