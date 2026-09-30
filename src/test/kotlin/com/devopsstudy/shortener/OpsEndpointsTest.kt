package com.devopsstudy.shortener

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals

@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class OpsEndpointsTest(
	@Autowired private val mockMvc: MockMvc,
) {
	@Test
	fun `liveness 프로브는 UP이다`() {
		mockMvc.get("/actuator/health/liveness").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
		}
	}

	@Test
	fun `readiness 프로브는 DB만 보고 Redis는 보지 않는다`() {
		mockMvc.get("/actuator/health/readiness").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
			jsonPath("$.components.db.status") { value("UP") }
			jsonPath("$.components.redis") { doesNotExist() }
		}
	}

	@Test
	fun `전체 health에는 Redis 상태도 나온다`() {
		mockMvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.components.redis.status") { value("UP") }
		}
	}

	@Test
	fun `info에 빌드 버전이 나온다`() {
		mockMvc.get("/actuator/info").andExpect {
			status { isOk() }
			jsonPath("$.build.artifact") { value("shortener") }
			jsonPath("$.build.version") { value("0.0.1-SNAPSHOT") }
			// commit은 CI가 넘긴 SHA일 수도, 로컬 기본값 "local"일 수도 있다. 개발 셸이나 CI에 GIT_COMMIT이 export돼 있어도 깨지지 않게 값은 고정하지 않고 비어 있지 않은 문자열인지만 본다
			jsonPath("$.build.commit") {
				isString()
				isNotEmpty()
			}
		}
	}

	@Test
	fun `prometheus 엔드포인트가 지연시간 히스토그램과 앱 메트릭을 내보낸다`() {
		mockMvc.get("/api/v1/urls/nosuch1") // http.server.requests 메트릭을 하나 남긴다

		val body = mockMvc.get("/actuator/prometheus").andExpect {
			status { isOk() }
		}.andReturn().response.contentAsString

		assertContains(body, "http_server_requests_seconds_bucket")
		assertContains(body, "shortener_cache_requests_total")
		assertContains(body, "shortener_urls_shortened_total")
		assertContains(body, "application=\"shortener\"")
	}

	@Test
	fun `로그는 한 줄짜리 JSON으로 찍힌다`(output: CapturedOutput) {
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/log"}"""
		}.andExpect { status { isCreated() } }

		val line = output.out.lines().last { it.contains("short url created") }
		val json = JsonMapper.builder().build().readTree(line)
		assertEquals("short url created", json.get("message").asString())
		assertEquals("INFO", json.at("/log/level").asString())
	}
}
