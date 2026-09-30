package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
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
	private val service = ShortUrlService(
		repository,
		Base62ShortCodeGenerator(),
		UrlCache(StringRedisTemplate(deadRedis), ShortenerProperties(), meterRegistry),
		meterRegistry,
	)

	@AfterEach
	fun tearDown() {
		deadRedis.destroy()
	}

	@Test
	fun `Redis에 연결할 수 없어도 원본 URL을 돌려주고 조회수를 센다`() {
		val code = UUID.randomUUID().toString().replace("-", "").take(12)
		repository.saveAndFlush(ShortUrl(code, "https://example.com/redis-down"))

		assertEquals("https://example.com/redis-down", service.resolve(code))
		assertEquals("https://example.com/redis-down", service.resolve(code))

		assertEquals(2L, repository.findByCode(code)?.clickCount)
		// 리다이렉트마다 캐시 조회(get)와 캐시 저장(put)이 둘 다 실패한다. Redis가 응답 없이 멈추면 이 두 번이 각각 타임아웃까지 기다린다
		assertEquals(4.0, meterRegistry.counter(UrlCache.METRIC, "result", "error").count())
	}
}
