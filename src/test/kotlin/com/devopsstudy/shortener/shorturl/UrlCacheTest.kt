package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class UrlCacheTest(
	@Autowired private val cache: UrlCache,
	@Autowired private val redis: StringRedisTemplate,
	@Autowired private val meterRegistry: MeterRegistry,
) {
	private fun uniqueCode() = UUID.randomUUID().toString().replace("-", "").take(12)

	private fun count(result: String) = meterRegistry.counter(UrlCache.METRIC, "result", result).count()

	@Test
	fun `넣은 원본 URL을 코드로 꺼낸다`() {
		val code = uniqueCode()
		cache.put(code, "https://example.com/x")

		assertEquals("https://example.com/x", cache.get(code))
		assertEquals("https://example.com/x", redis.opsForValue().get("short-url:$code"))
	}

	@Test
	fun `없는 코드는 null이다`() {
		assertNull(cache.get(uniqueCode()))
	}

	@Test
	fun `캐시 항목에는 TTL이 걸린다`() {
		val code = uniqueCode()
		cache.put(code, "https://example.com/ttl")

		val ttlSeconds = assertNotNull(redis.getExpire("short-url:$code"))
		assertTrue(ttlSeconds in 1L..86_400L, "ttl=$ttlSeconds")
	}

	@Test
	fun `적중과 실패를 메트릭으로 센다`() {
		val code = uniqueCode()
		val hitsBefore = count("hit")
		val missesBefore = count("miss")

		cache.get(code)
		cache.put(code, "https://example.com/m")
		cache.get(code)

		assertEquals(missesBefore + 1, count("miss"))
		assertEquals(hitsBefore + 1, count("hit"))
	}
}
