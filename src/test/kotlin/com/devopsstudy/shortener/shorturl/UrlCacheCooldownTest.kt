package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Redis가 실패하면 cooldown(10초) 동안 Redis를 부르지 않고 건너뛰는지를 컨테이너 없이 확인한다.
 * 가짜 Redis(FakeRedis)로 장애를 켜고 끄고, 시간은 TestTimeSource로 테스트가 직접 움직인다.
 */
class UrlCacheCooldownTest {
	private val redis = FakeRedis()
	private val meterRegistry = SimpleMeterRegistry()
	private val time = TestTimeSource()
	private val cache = UrlCache(redis.template, ShortenerProperties(cacheCooldown = Duration.ofSeconds(10)), meterRegistry, time)
	private val logs = LogCapture(UrlCache::class.java)

	@AfterEach
	fun tearDown() {
		logs.close()
	}

	private fun count(result: String) = meterRegistry.counter(UrlCache.METRIC, "result", result).count()

	@Test
	fun `Redis가 멀쩡하면 적중과 미스를 세고 건너뛰지 않는다`() {
		redis.store["${UrlCache.KEY_PREFIX}abc1234"] = "https://example.com/a"

		assertEquals("https://example.com/a", cache.get("abc1234"))
		assertNull(cache.get("nosuch1"))
		cache.put("new1234", "https://example.com/new")

		assertEquals(1.0, count("hit"))
		assertEquals(1.0, count("miss"))
		assertEquals(0.0, count("skipped"))
		assertEquals(3, redis.calls.get())
		assertEquals("https://example.com/new", redis.store["${UrlCache.KEY_PREFIX}new1234"])
	}

	@Test
	fun `건너뛴 수는 Redis가 실패하기 전에도 0으로 등록돼 있다`() {
		assertEquals(0.0, count("skipped"))
	}

	@Test
	fun `get이 실패하면 그 뒤 10초 동안 Redis를 부르지 않고 건너뛴 조회를 skipped로 센다`() {
		redis.down = true

		assertNull(cache.get("abc1234"))
		assertEquals(1, redis.calls.get())
		assertEquals(1.0, count("error"))

		// 같은 요청의 put(캐시 미스처럼 DB에서 읽은 뒤 넣는 것)과 뒤따르는 요청들은 Redis에 가지 않는다
		cache.put("abc1234", "https://example.com/a")
		repeat(3) { assertNull(cache.get("abc1234")) }
		time += 9.seconds
		assertNull(cache.get("abc1234"))

		assertEquals(1, redis.calls.get(), "타임아웃을 치르는 Redis 호출은 처음 한 번뿐이다")
		assertEquals(1.0, count("error"))
		assertEquals(4.0, count("skipped"), "건너뛴 get만 센다 (put은 세지 않는다)")
	}

	@Test
	fun `put이 실패해도 창이 열린다`() {
		redis.down = true

		cache.put("abc1234", "https://example.com/a")
		assertNull(cache.get("abc1234"))

		assertEquals(1, redis.calls.get())
		assertEquals(1.0, count("error"))
		assertEquals(1.0, count("skipped"))
	}

	@Test
	fun `10초가 지나면 한 번 시험해 보고 Redis가 돌아왔으면 다시 캐시를 쓴다`() {
		redis.down = true
		cache.get("abc1234")
		redis.down = false
		redis.store["${UrlCache.KEY_PREFIX}abc1234"] = "https://example.com/a"

		assertNull(cache.get("abc1234"), "Redis가 돌아왔어도 창 안에서는 건너뛴다")
		time += 10.seconds

		assertEquals("https://example.com/a", cache.get("abc1234"), "시험 호출")
		assertEquals("https://example.com/a", cache.get("abc1234"))
		assertEquals(3, redis.calls.get())
		assertEquals(2.0, count("hit"))
	}

	@Test
	fun `시험 호출이 실패하면 다시 10초 동안 건너뛴다`() {
		redis.down = true
		cache.get("abc1234")
		time += 10.seconds

		assertNull(cache.get("abc1234"))
		assertEquals(2, redis.calls.get(), "시험 호출")
		repeat(5) { cache.get("abc1234") }
		time += 9.seconds
		cache.get("abc1234")

		assertEquals(2, redis.calls.get())
		assertEquals(2.0, count("error"))
		assertEquals(6.0, count("skipped"))
	}

	@Test
	fun `경고 로그는 창마다 한 줄이고 그사이 더 난 실패 수를 suppressed로 남긴다`() {
		// Redis가 멈춘 순간에 이미 Redis를 기다리던 요청 두 개를 만든다: 둘 다 Redis에 들어온 뒤에야 함께 실패한다
		redis.down = true
		val bothWaiting = CountDownLatch(2)
		redis.beforeCommand = {
			bothWaiting.countDown()
			bothWaiting.await(5, TimeUnit.SECONDS)
		}
		val pool = Executors.newFixedThreadPool(2)
		try {
			val requests = List(2) { pool.submit<String?> { cache.get("abc1234") } }
			requests.forEach { assertNull(it.get(5, TimeUnit.SECONDS)) }
		} finally {
			pool.shutdownNow()
		}
		redis.beforeCommand = {}

		assertEquals(2.0, count("error"))
		val first = logs.warnings().single()
		assertEquals("redis get failed, falling back to db", first.formattedMessage)
		assertEquals(0L, first.fields()["suppressed"])
		assertEquals("abc1234", first.fields()["code"])
		assertEquals("PT10S", first.fields()["cooldown"])

		time += 10.seconds
		cache.get("abc1234")

		val warnings = logs.warnings()
		assertEquals(2, warnings.size, "다음 창의 시험 호출 실패가 한 줄 더 남는다")
		assertEquals(1L, warnings.last().fields()["suppressed"], "첫 창에서 거른 실패 한 건")
		assertTrue(warnings.none { it.throwableProxy != null }, "스택트레이스는 남기지 않는다")
	}
}
