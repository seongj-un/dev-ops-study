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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
	fun `readiness 프로브는 앱 자신의 상태만 보고 DB와 Redis는 보지 않는다`() {
		// DB를 넣으면 DB 장애 때 모든 파드가 함께 NotReady가 되어, 캐시로 답할 수 있던 리다이렉트까지 막힌다 (application.yml 참고)
		mockMvc.get("/actuator/health/readiness").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
			jsonPath("$.components.readinessState.status") { value("UP") }
			jsonPath("$.components.db") { doesNotExist() }
			jsonPath("$.components.redis") { doesNotExist() }
		}
	}

	@Test
	fun `전체 health에는 DB와 Redis 상태가 나온다`() {
		mockMvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.components.db.status") { value("UP") }
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
		assertContains(body, "shortener_clicks_recorded_total")
		assertContains(body, "shortener_clicks_dropped_total")
		assertContains(body, "application=\"shortener\"")
	}

	@Test
	fun `지연시간 히스토그램에 SLO 경계 100ms, 300ms, 1s의 정확한 버킷이 있다`() {
		mockMvc.get("/api/v1/urls/nosuch1") // 앱 요청의 http.server.requests 메트릭을 하나 남긴다 (SLI는 /actuator를 뺀 앱 요청이다)

		val body = mockMvc.get("/actuator/prometheus").andExpect {
			status { isOk() }
		}.andReturn().response.contentAsString

		// 시계열 하나(uri 하나)의 버킷 경계(le 레이블 값)만 모은다
		val leLabel = Regex("""le="([^"]+)"""")
		val upperBounds = body.lines()
			.filter { it.startsWith("http_server_requests_seconds_bucket{") && it.contains("uri=\"/api/v1/urls/{code}\"") }
			.map { assertNotNull(leLabel.find(it), "버킷 줄에 le 레이블이 없다: $it").groupValues[1] }
			.toSet()

		// Prometheus가 "300ms 이하" 요청 수를 정확히 세려면 경계가 0.3초인 버킷이 있어야 한다 (히스토그램 기본 버킷에는 0.3초가 없다)
		assertContains(upperBounds, "0.1")
		assertContains(upperBounds, "0.3")
		assertContains(upperBounds, "1.0")
		// SLO 경계는 percentiles-histogram의 기본 버킷에 더해지는 것이라 둘 다 있어야 한다. SLO 경계 세 개만 남으면 p95/p99를 구할 촘촘한 버킷이 사라진다
		assertTrue(upperBounds.size > 10, "percentiles-histogram의 기본 버킷도 남아 있어야 한다: $upperBounds")
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
