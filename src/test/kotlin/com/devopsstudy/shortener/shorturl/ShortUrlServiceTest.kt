package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 코드 충돌 처리를 확인한다. 생성기를 "정해진 코드를 차례로 내주는 람다"로 바꿔 끼워 충돌을 일부러 만든다. */
@IntegrationTest
class ShortUrlServiceTest(
	@Autowired private val repository: ShortUrlRepository,
	@Autowired private val cache: UrlCache,
	@Autowired private val meterRegistry: MeterRegistry,
) {
	private fun uniqueCode() = UUID.randomUUID().toString().replace("-", "").take(12)

	private fun serviceGenerating(vararg codes: String): ShortUrlService {
		val next = codes.iterator()
		return ShortUrlService(repository, ShortCodeGenerator { next.next() }, cache, meterRegistry)
	}

	@Test
	fun `코드가 겹치면 새 코드로 다시 시도한다`() {
		val taken = uniqueCode()
		repository.saveAndFlush(ShortUrl(taken, "https://example.com/taken"))
		val fresh = uniqueCode()
		val createdBefore = meterRegistry.counter("shortener.urls.shortened").count()

		val created = serviceGenerating(taken, taken, fresh).create("https://example.com/new")

		assertEquals(fresh, created.code)
		assertEquals("https://example.com/new", repository.findByCode(fresh)?.originalUrl)
		assertEquals(createdBefore + 1, meterRegistry.counter("shortener.urls.shortened").count())
	}

	@Test
	fun `5번 모두 겹치면 포기한다`() {
		val taken = uniqueCode()
		repository.saveAndFlush(ShortUrl(taken, "https://example.com/taken"))
		val service = serviceGenerating(*Array(ShortUrlService.MAX_ATTEMPTS) { taken })

		assertFailsWith<IllegalStateException> { service.create("https://example.com/never") }
	}
}
