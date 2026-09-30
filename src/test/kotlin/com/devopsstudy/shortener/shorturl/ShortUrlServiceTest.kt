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
		// 포기하기까지 생성기를 몇 번 불렀는지 센다: 1~4번 만에 포기하는 구현은 이 테스트를 통과하면 안 된다
		var attempts = 0
		val service = ShortUrlService(repository, ShortCodeGenerator { attempts++; taken }, cache, meterRegistry)

		assertFailsWith<IllegalStateException> { service.create("https://example.com/never") }
		assertEquals(ShortUrlService.MAX_ATTEMPTS, attempts)
	}

	@Test
	fun `너무 긴 URL은 코드 충돌로 오해하지 않고 바로 거절한다`() {
		// API의 DTO 검증을 거치지 않고 서비스를 직접 부르면 DB의 VARCHAR(2048) 제약 위반이 5번 충돌로 보였다
		val tooLong = "https://example.com/" + "a".repeat(ShortUrlService.MAX_URL_LENGTH)

		val service = serviceGenerating(*Array(ShortUrlService.MAX_ATTEMPTS) { uniqueCode() })

		assertFailsWith<InvalidUrlException> { service.create(tooLong) }
	}
}
