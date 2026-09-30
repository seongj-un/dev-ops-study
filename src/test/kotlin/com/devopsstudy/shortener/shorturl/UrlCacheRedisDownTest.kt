package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 연결할 수 없는 Redis(deadRedisConnectionFactory)에 붙인 UrlCache가 예외를 밖으로 던지지 않는지 본다. */
class UrlCacheRedisDownTest {
	private val connectionFactory = deadRedisConnectionFactory()
	private val meterRegistry = SimpleMeterRegistry()
	private val cache = UrlCache(StringRedisTemplate(connectionFactory), ShortenerProperties(), meterRegistry)

	@AfterEach
	fun tearDown() {
		connectionFactory.destroy()
	}

	private fun errorCount() = meterRegistry.counter(UrlCache.METRIC, "result", "error").count()

	@Test
	fun `Redis에 연결할 수 없으면 get은 예외 대신 null을 돌려준다`() {
		assertNull(cache.get("abc1234"))
		assertEquals(1.0, errorCount())
	}

	@Test
	fun `Redis에 연결할 수 없어도 put은 예외를 던지지 않는다`() {
		cache.put("abc1234", "https://example.com")
		assertEquals(1.0, errorCount())
	}
}
