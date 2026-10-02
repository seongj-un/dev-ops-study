package com.devopsstudy.shortener.fault

import com.devopsstudy.shortener.IntegrationTest
import com.devopsstudy.shortener.shorturl.ShortUrlRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.random.RandomGenerator
import kotlin.test.assertEquals

/**
 * 장애 주입이 켜진 앱 전체(필터 체인, 관측, actuator, 로그)를 확인한다. 비율은 0.5로 두고 난수원을 가짜로 바꿔서, 요청마다 장애가 날지 말지를 테스트가 정한다.
 * 설정이 다르면 컨텍스트(와 Testcontainers 컨테이너)가 따로 뜨므로 장애 주입이 켜진 통합 테스트는 이 클래스에 모은다.
 * 서버를 실제로 띄워야 하는 배치(관리 포트를 앱 포트와 분리)만 FaultInjectionManagementPortTest가 맡는다.
 */
@IntegrationTest
@TestPropertySource(properties = ["shortener.fault.error-rate=0.5"])
@ExtendWith(OutputCaptureExtension::class)
class FaultInjectionTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val random: ControlledRandom,
	@Autowired private val meterRegistry: MeterRegistry,
	@Autowired private val repository: ShortUrlRepository,
) {
	/** nextDouble()이 draw를 내준다. 0.5(비율)보다 작으면 장애가 나고 0.5 이상이면 통과한다. */
	class ControlledRandom : RandomGenerator {
		@Volatile
		var draw = NO_FAULT

		override fun nextLong(): Long = error("필터는 nextDouble()만 쓴다")
		override fun nextDouble(): Double = draw
	}

	@TestConfiguration(proxyBeanMethods = false)
	class ControlledRandomConfiguration {
		@Bean
		@Primary
		fun controlledRandom() = ControlledRandom()
	}

	private fun forceFault() {
		random.draw = FAULT
	}

	private fun forceNoFault() {
		random.draw = NO_FAULT
	}

	private fun prometheus(): String = mockMvc.get("/actuator/prometheus").andExpect {
		status { isOk() }
	}.andReturn().response.contentAsString

	@Test
	fun `난수가 비율보다 작으면 앱 요청은 problem+json 500을 받는다`() {
		forceFault()

		// 이 경로는 평소 404다. 500이 나온다면 컨트롤러가 아니라 필터가 끊은 것이다
		mockMvc.get("/api/v1/urls/nosuch1").andExpect {
			status { isInternalServerError() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(500) }
			jsonPath("$.title") { value("Internal Server Error") }
			jsonPath("$.detail") { value("fault injected by shortener.fault.error-rate") }
			jsonPath("$.instance") { value("/api/v1/urls/nosuch1") }
		}
	}

	@Test
	fun `난수가 비율 이상이면 평소처럼 처리하고 장애로 세지 않는다`() {
		forceNoFault()
		val injectedBefore = prometheus().faultInjectedTotal()

		mockMvc.get("/api/v1/urls/nosuch1").andExpect {
			status { isNotFound() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/no-fault"}"""
		}.andExpect { status { isCreated() } }

		assertEquals(injectedBefore, prometheus().faultInjectedTotal())
	}

	@Test
	fun `장애가 난 요청은 컨트롤러와 DB에 닿지 않는다`() {
		val rowsBefore = repository.count()
		val shortenedBefore = meterRegistry.counter("shortener.urls.shortened").count()
		forceFault()

		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/never-saved"}"""
		}.andExpect { status { isInternalServerError() } }

		assertEquals(rowsBefore, repository.count())
		assertEquals(shortenedBefore, meterRegistry.counter("shortener.urls.shortened").count())
	}

	@Test
	fun `actuator 요청은 난수가 장애를 가리켜도 성공한다`() {
		// 관리 포트를 앱 포트와 합친 경우(MockMvc, 로컬 기본). 포트를 따로 둔 경우는 FaultInjectionManagementPortTest가 본다
		forceFault()

		listOf("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health", "/actuator/info", "/actuator/prometheus").forEach { path ->
			mockMvc.get(path).andExpect { status { isOk() } }
		}
	}

	@Test
	fun `주입한 500은 http_server_requests의 status 500으로 잡힌다`() {
		// 필터가 관측 필터(ServerHttpObservationFilter)보다 앞에 있으면 끊긴 요청이 관측을 거치지 않아 이 계열이 늘지 않는다
		val before = prometheus().server500Count()
		forceFault()

		repeat(2) { mockMvc.get("/api/v1/urls/nosuch1").andExpect { status { isInternalServerError() } } }
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/recorded"}"""
		}.andExpect { status { isInternalServerError() } }

		assertEquals(before + 3, prometheus().server500Count())
	}

	@Test
	fun `장애를 낼 때마다 shortener_fault_injected_total이 오른다`() {
		val before = prometheus().faultInjectedTotal()
		forceFault()

		repeat(3) { mockMvc.get("/api/v1/urls/nosuch1").andExpect { status { isInternalServerError() } } }

		assertEquals(before + 3, prometheus().faultInjectedTotal())
	}

	@Test
	fun `장애 한 건마다 WARN 로그가 ECS JSON 한 줄로 남는다`(output: CapturedOutput) {
		fun faultLines() = output.out.lines().filter { it.contains("\"message\":\"fault injected\"") }
		val before = faultLines().size
		forceFault()

		mockMvc.get("/api/v1/urls/log-check1").andExpect { status { isInternalServerError() } }
		mockMvc.post("/api/v1/urls") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"url": "https://example.com/log"}"""
		}.andExpect { status { isInternalServerError() } }
		forceNoFault()
		mockMvc.get("/api/v1/urls/log-check2").andExpect { status { isNotFound() } }

		val lines = faultLines()
		assertEquals(before + 2, lines.size, "장애 두 건에 로그 두 줄, 통과한 요청은 로그가 없어야 한다")
		val json = JsonMapper.builder().build().readTree(lines.last())
		assertEquals("WARN", json.at("/log/level").asString())
		assertEquals("POST", json.get("method").asString())
		assertEquals("/api/v1/urls", json.get("path").asString())
	}

	private companion object {
		const val FAULT = 0.0
		const val NO_FAULT = 0.99
	}
}
