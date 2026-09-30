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
	fun `생성 응답과 이후 조회 응답의 createdAt이 같다`() {
		val created = mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/created-at"}"""
		}.andExpect {
			status { isCreated() }
		}.andReturn().response.contentAsString
		val code = JsonPath.read<String>(created, "$.code")

		// 생성 응답은 메모리의 값이고 조회 응답은 DB에서 읽은 값이다. TIMESTAMPTZ는 마이크로초까지만 저장하므로
		// 시각에 나노초가 남아 있으면(Linux) 두 응답이 어긋난다.
		mockMvc.get("/api/v1/urls/$code").andExpect {
			status { isOk() }
			jsonPath("$.createdAt") { value(JsonPath.read<String>(created, "$.createdAt")) }
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
	fun `javascript 스킴 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": "javascript:alert(1)"}""")
	}

	@Test
	fun `사용자 정보가 들어간 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": "https://user:pass@example.com/x"}""")
	}

	@Test
	fun `2048자를 넘는 URL은 400이다`() {
		postUrlExpectingBadRequest("""{"url": "https://example.com/${"a".repeat(2048)}"}""")
	}

	@Test
	fun `정확히 2048자인 URL은 받아 준다`() {
		val prefix = "https://example.com/"
		createShortUrl(prefix + "a".repeat(ShortUrlService.MAX_URL_LENGTH - prefix.length))
	}

	@Test
	fun `대문자 스킴 URL도 받아 준다`() {
		createShortUrl("HTTPS://EXAMPLE.COM/upper")
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
