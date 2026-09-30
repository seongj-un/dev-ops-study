package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
		assertNotNull(found.createdAt)
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
	fun `없는 코드의 조회수를 올리면 바뀐 행이 없다`() {
		assertEquals(0, repository.incrementClickCount("no-such-code"))
	}
}
