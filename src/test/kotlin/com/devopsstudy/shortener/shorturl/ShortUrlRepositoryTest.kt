package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
class ShortUrlRepositoryTest(
	@Autowired private val repository: ShortUrlRepository,
) {
	private fun uniqueCode() = UUID.randomUUID().toString().replace("-", "").take(12)

	@Test
	fun `저장한 단축 URL을 코드로 찾는다`() {
		val code = uniqueCode()
		repository.saveAndFlush(ShortUrl(code, "https://example.com/a"))

		val found = assertNotNull(repository.findByCode(code))
		assertNotNull(found.id)
		assertEquals("https://example.com/a", found.originalUrl)
		assertEquals(0L, found.clickCount)
		assertTrue(Duration.between(found.createdAt, Instant.now()).abs() < Duration.ofMinutes(1), "createdAt=${found.createdAt}")
	}

	@Test
	fun `없는 코드는 null이다`() {
		assertNull(repository.findByCode("no-such-code"))
	}

	@Test
	fun `같은 코드를 두 번 저장하면 유니크 제약에 걸린다`() {
		val code = uniqueCode()
		repository.saveAndFlush(ShortUrl(code, "https://example.com/1"))

		assertFailsWith<DataIntegrityViolationException> {
			repository.saveAndFlush(ShortUrl(code, "https://example.com/2"))
		}
	}

	@Test
	fun `조회수를 UPDATE 한 번으로 1씩 올린다`() {
		val code = uniqueCode()
		repository.saveAndFlush(ShortUrl(code, "https://example.com/c"))

		assertEquals(1, repository.incrementClickCount(code))
		assertEquals(1, repository.incrementClickCount(code))

		assertEquals(2L, repository.findByCode(code)?.clickCount)
	}

	@Test
	fun `동시에 조회수를 올려도 유실되지 않는다`() {
		val code = uniqueCode()
		repository.saveAndFlush(ShortUrl(code, "https://example.com/concurrent"))
		val threads = 8
		val incrementsPerThread = 25

		// "읽고 → 더하고 → 저장"이었다면 동시 요청이 서로의 값을 덮어써 200보다 작아진다. 원자적 UPDATE가 있는 이유다.
		val pool = Executors.newFixedThreadPool(threads)
		try {
			val futures = List(threads) {
				pool.submit { repeat(incrementsPerThread) { repository.incrementClickCount(code) } }
			}
			futures.forEach { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		assertEquals(200L, repository.findByCode(code)?.clickCount)
	}

	@Test
	fun `없는 코드의 조회수를 올리면 바뀐 행이 없다`() {
		assertEquals(0, repository.incrementClickCount("no-such-code"))
	}
}
