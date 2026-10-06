package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/**
 * "Redis가 죽어도 요청은 실패하지 않는다"를 서비스 수준에서 확인한다.
 * DB는 진짜(Testcontainers PostgreSQL)를 쓰고, 캐시만 연결할 수 없는 Redis에 붙인다.
 */
@IntegrationTest
class ShortUrlServiceRedisDownTest(
	@Autowired private val repository: ShortUrlRepository,
) {
	private val deadRedis = deadRedisConnectionFactory()
	private val meterRegistry = SimpleMeterRegistry()
	private val clickRecorder = ClickRecorder(
		executor = ClickRecorder.boundedExecutor(threads = 1, queueCapacity = 10),
		write = { code -> repository.incrementClickCount(code) },
		meterRegistry = meterRegistry,
		shutdownTimeout = Duration.ofSeconds(10),
	)
	private val service = ShortUrlService(
		repository,
		Base62ShortCodeGenerator(),
		UrlCache(StringRedisTemplate(deadRedis), ShortenerProperties(), meterRegistry),
		clickRecorder,
		meterRegistry,
	)

	@AfterEach
	fun tearDown() {
		clickRecorder.close()
		deadRedis.destroy()
	}

	@Test
	fun `Redis에 연결할 수 없어도 원본 URL을 돌려주고 조회수를 센다`() {
		val code = UUID.randomUUID().toString().replace("-", "").take(12)
		repository.saveAndFlush(ShortUrl(code, "https://example.com/redis-down"))

		assertEquals("https://example.com/redis-down", service.resolve(code))
		assertEquals("https://example.com/redis-down", service.resolve(code))

		// 조회수는 따로 도는 스레드가 쓴다. close()가 대기열이 빌 때까지 기다린다
		clickRecorder.close()
		assertEquals(2L, repository.findByCode(code)?.clickCount)
		// 첫 리다이렉트의 캐시 조회(get)만 Redis까지 가서 실패한다. 그 뒤로는 cooldown(10초) 동안 Redis를 부르지 않는다:
		// 같은 요청의 캐시 저장(put)은 그냥 건너뛰고, 두 번째 리다이렉트의 get은 건너뛴 조회(skipped)로 센다
		assertEquals(1.0, meterRegistry.counter(UrlCache.METRIC, "result", "error").count())
		assertEquals(1.0, meterRegistry.counter(UrlCache.METRIC, "result", "skipped").count())
	}
}
