package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.IntegrationTest
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNull

@IntegrationTest
class ShortUrlApiTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val redis: StringRedisTemplate,
) {
	private fun createShortUrl(url: String): String {
		val body = mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "$url"}"""
		}.andExpect {
			status { isCreated() }
		}.andReturn().response.contentAsString
		return JsonPath.read(body, "$.code")
	}

	private fun postUrlExpectingBadRequest(json: String) {
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = json
		}.andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
	}

	@Test
	fun `단축 URL을 만들면 201과 단축 주소를 돌려준다`() {
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/docs?page=1"}"""
		}.andExpect {
			status { isCreated() }
			header { string("Location", matchesPattern("http://localhost:8080/[0-9a-zA-Z]{7}")) }
			jsonPath("$.code") { value(matchesPattern("[0-9a-zA-Z]{7}")) }
			jsonPath("$.shortUrl") { value(matchesPattern("http://localhost:8080/[0-9a-zA-Z]{7}")) }
			jsonPath("$.originalUrl") { value("https://example.com/docs?page=1") }
			jsonPath("$.clickCount") { value(0) }
			jsonPath("$.createdAt") { exists() }
		}
	}

	@Test
	fun `http나 https가 아닌 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": "ftp://example.com/file"}""")
	}

	@Test
	fun `호스트가 없는 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": "/just/a/path"}""")
	}

	@Test
	fun `빈 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": ""}""")
	}

	@Test
	fun `url 필드가 없으면 400이다`() {
		postUrlExpectingBadRequest("""{}""")
	}

	@Test
	fun `단축 코드로 접속하면 원본 URL로 302 리다이렉트한다`() {
		val code = createShortUrl("https://example.com/target")

		mockMvc.get("/$code").andExpect {
			status { isFound() }
			header { string("Location", "https://example.com/target") }
		}
	}

	@Test
	fun `리다이렉트할 때마다 조회수가 오른다`() {
		val code = createShortUrl("https://example.com/count")

		repeat(2) { mockMvc.get("/$code").andExpect { status { isFound() } } }

		mockMvc.get("/api/v1/urls/$code").andExpect {
			status { isOk() }
			jsonPath("$.code") { value(code) }
			jsonPath("$.shortUrl") { value("http://localhost:8080/$code") }
			jsonPath("$.originalUrl") { value("https://example.com/count") }
			jsonPath("$.clickCount") { value(2) }
		}
	}

	@Test
	fun `첫 리다이렉트 뒤에는 원본 URL이 Redis에 캐시된다`() {
		val code = createShortUrl("https://example.com/cached")
		assertNull(redis.opsForValue().get("${UrlCache.KEY_PREFIX}$code"))

		mockMvc.get("/$code").andExpect { status { isFound() } }

		assertEquals("https://example.com/cached", redis.opsForValue().get("${UrlCache.KEY_PREFIX}$code"))
	}

	@Test
	fun `없는 코드로 접속하면 404이다`() {
		mockMvc.get("/nosuch1").andExpect {
			status { isNotFound() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
	}

	@Test
	fun `없는 코드의 통계를 조회하면 404이다`() {
		mockMvc.get("/api/v1/urls/nosuch1").andExpect {
			status { isNotFound() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
	}
}
