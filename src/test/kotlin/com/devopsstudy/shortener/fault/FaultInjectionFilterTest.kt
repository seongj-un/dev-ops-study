package com.devopsstudy.shortener.fault

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.json.ProblemDetailJacksonMixin
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.util.random.RandomGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 장애 주입 필터의 판정과 응답을 스프링 컨텍스트 없이 확인한다. 난수원을 정해진 값을 내주는 가짜로 바꿔 끼워서 장애가 날지 말지를 테스트가 정한다.
 * 필터가 실제 서블릿 체인에서 관측 필터보다 뒤에 있는지, 관리 포트가 따로 있을 때 거치지 않는지는 FaultInjectionTest와 FaultInjectionManagementPortTest가 본다.
 */
class FaultInjectionFilterTest {
	private val meterRegistry = SimpleMeterRegistry()

	// Boot가 자동 구성하는 JsonMapper와 같게 ProblemDetail 믹스인을 붙인다 (앱의 400·404 응답과 같은 필드 모양이 나온다)
	private val jsonMapper = JsonMapper.builder().addMixIn(ProblemDetail::class.java, ProblemDetailJacksonMixin::class.java).build()

	private val logger = LoggerFactory.getLogger(FaultInjectionFilter::class.java) as Logger
	private val logEvents = ListAppender<ILoggingEvent>()

	@BeforeEach
	fun attachLogAppender() {
		logEvents.start()
		logger.addAppender(logEvents)
	}

	@AfterEach
	fun detachLogAppender() {
		logger.detachAppender(logEvents)
	}

	/** nextDouble()이 늘 value를 내주는 난수원. 장애가 날지 말지를 이 값으로 정한다. */
	private fun drawing(value: Double) = object : RandomGenerator {
		override fun nextLong(): Long = error("필터는 nextDouble()만 쓴다")
		override fun nextDouble(): Double = value
	}

	private fun filter(errorRate: Double, draw: Double, actuatorBasePath: String = "/actuator") =
		FaultInjectionFilter(errorRate, drawing(draw), actuatorBasePath, jsonMapper, meterRegistry)

	private class Outcome(val response: MockHttpServletResponse, val chain: MockFilterChain) {
		/** 필터가 요청을 컨트롤러 쪽으로 넘겼는가 */
		val passedThrough get() = chain.request != null
	}

	private fun send(
		filter: FaultInjectionFilter,
		path: String = "/aB3xY9z",
		method: String = "GET",
		contextPath: String = "",
	): Outcome {
		val request = MockHttpServletRequest(method, path).apply { this.contextPath = contextPath }
		val response = MockHttpServletResponse()
		val chain = MockFilterChain()
		filter.doFilter(request, response, chain)
		return Outcome(response, chain)
	}

	private fun injectedCount() = meterRegistry.counter("shortener.fault.injected").count()

	private fun warnings() = logEvents.list.filter { it.level == Level.WARN }

	@Test
	fun `비율이 0이면 난수가 0이어도 장애를 내지 않는다`() {
		// nextDouble()이 줄 수 있는 가장 낮은 값(0.0)에도 장애가 나지 않아야 "한 번도 안 낸다"가 성립한다
		val outcome = send(filter(errorRate = 0.0, draw = 0.0))

		assertTrue(outcome.passedThrough)
		assertEquals(200, outcome.response.status)
		assertEquals(0.0, injectedCount())
		assertEquals(0, warnings().size)
	}

	@Test
	fun `비율이 1이면 난수가 가장 커도 장애를 낸다`() {
		// nextDouble()은 1.0 미만이므로 줄 수 있는 가장 큰 값은 1.0 바로 아래다
		val outcome = send(filter(errorRate = 1.0, draw = Math.nextDown(1.0)))

		assertFalse(outcome.passedThrough)
		assertEquals(500, outcome.response.status)
	}

	@Test
	fun `난수가 비율보다 작으면 장애를 내고 같거나 크면 통과시킨다`() {
		val belowRate = send(filter(errorRate = 0.5, draw = Math.nextDown(0.5)))
		val atRate = send(filter(errorRate = 0.5, draw = 0.5))

		assertEquals(500, belowRate.response.status)
		assertTrue(atRate.passedThrough)
		assertEquals(200, atRate.response.status)
		assertEquals(1.0, injectedCount())
	}

	@Test
	fun `비율이 NaN이어도 장애를 내지 않는다`() {
		// 설정 검증이 NaN을 막지만, 그 검증이 어긋나도 모든 요청을 장애로 만들지는 않아야 한다
		val outcome = send(filter(errorRate = Double.NaN, draw = 0.0))

		assertTrue(outcome.passedThrough)
	}

	@Test
	fun `장애는 problem+json 형식의 500이고 컨트롤러에 닿지 않는다`() {
		val outcome = send(filter(errorRate = 1.0, draw = 0.0), path = "/api/v1/urls/abc1234")

		assertEquals(500, outcome.response.status)
		assertEquals("application/problem+json", outcome.response.contentType)
		assertFalse(outcome.passedThrough)
		// 앱의 다른 에러 응답(400·404)과 같은 RFC 9457 필드다. type은 about:blank(기본값)라서 그쪽 응답처럼 빠진다.
		val body = jsonMapper.readTree(outcome.response.contentAsByteArray)
		assertEquals(setOf("detail", "instance", "status", "title"), body.propertyNames().toSet())
		assertEquals("Internal Server Error", body.get("title").asString())
		assertEquals(500, body.get("status").asInt())
		assertEquals("fault injected by shortener.fault.error-rate", body.get("detail").asString())
		assertEquals("/api/v1/urls/abc1234", body.get("instance").asString())
	}

	@Test
	fun `URI로 해석되지 않는 경로로 와도 500 응답을 만든다`() {
		// "//"는 URI로 읽으면 권한(authority)이 비어 있어 문법 오류다. 이런 요청 때문에 장애 응답을 만들다 예외가 나면 안 된다 (instance만 빠진다)
		val outcome = send(filter(errorRate = 1.0, draw = 0.0), path = "//")

		assertEquals(500, outcome.response.status)
		assertEquals("application/problem+json", outcome.response.contentType)
		val body = jsonMapper.readTree(outcome.response.contentAsByteArray)
		assertEquals(setOf("detail", "status", "title"), body.propertyNames().toSet())
	}

	@Test
	fun `장애 한 건마다 카운터가 1 오르고 WARN 로그가 한 줄 남는다`() {
		val filter = filter(errorRate = 1.0, draw = 0.0)

		repeat(3) { send(filter, path = "/aB3xY9z", method = "POST") }

		assertEquals(3.0, injectedCount())
		val warnings = warnings()
		assertEquals(3, warnings.size)
		val warning = warnings.first()
		assertEquals("fault injected", warning.formattedMessage)
		assertEquals(mapOf("method" to "POST", "path" to "/aB3xY9z"), warning.keyValuePairs.associate { it.key to it.value })
	}

	@Test
	fun `통과시킨 요청은 카운터도 로그도 남기지 않는다`() {
		val outcome = send(filter(errorRate = 0.5, draw = 0.9))

		assertTrue(outcome.passedThrough)
		assertEquals(0.0, injectedCount())
		assertEquals(0, warnings().size)
	}

	@Test
	fun `필터를 만들면 카운터가 0으로 먼저 등록된다`() {
		// 장애가 나기 전에도 shortener_fault_injected_total 0이 보여야 대시보드와 쿼리가 "데이터 없음"이 되지 않는다
		filter(errorRate = 0.0, draw = 0.0)

		assertNotNull(meterRegistry.find("shortener.fault.injected").counter())
		assertEquals(0.0, injectedCount())
	}

	@ParameterizedTest(name = "{0}은 비율이 1이어도 장애를 내지 않는다")
	@ValueSource(strings = ["/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus", "/actuator/info"])
	fun `actuator 경로는 장애를 내지 않는다`(path: String) {
		// 관리 포트를 앱 포트와 합친 경우(로컬 기본)에도 프로브와 메트릭 수집은 장애 대상이 아니다
		val outcome = send(filter(errorRate = 1.0, draw = 0.0), path = path)

		assertTrue(outcome.passedThrough)
		assertEquals(200, outcome.response.status)
		assertEquals(0.0, injectedCount())
	}

	@ParameterizedTest(name = "{0}은 actuator가 아니라서 장애를 낸다")
	@ValueSource(strings = ["/", "/actuatorx", "/actuator-health", "/api/v1/urls/actuator", "/api/actuator/health", "/Actuator/health"])
	fun `actuator처럼 보여도 그 경로 아래가 아니면 장애를 낸다`(path: String) {
		// /actuatorx는 단축 코드로 쓸 수 있는 경로다. 접두어만 보고 건너뛰면 그 코드의 리다이렉트는 장애 대상에서 빠진다
		val outcome = send(filter(errorRate = 1.0, draw = 0.0), path = path)

		assertEquals(500, outcome.response.status)
	}

	@Test
	fun `컨텍스트 경로가 있어도 그 아래의 actuator 경로는 건너뛴다`() {
		val filter = filter(errorRate = 1.0, draw = 0.0)

		val actuator = send(filter, path = "/app/actuator/health", contextPath = "/app")
		val application = send(filter, path = "/app/aB3xY9z", contextPath = "/app")

		assertTrue(actuator.passedThrough)
		assertEquals(500, application.response.status)
	}

	@Test
	fun `actuator 기본 경로를 바꾸면 바꾼 경로를 건너뛴다`() {
		val filter = filter(errorRate = 1.0, draw = 0.0, actuatorBasePath = "/management")

		val moved = send(filter, path = "/management/health/liveness")
		val old = send(filter, path = "/actuator/health")

		assertTrue(moved.passedThrough)
		assertEquals(500, old.response.status)
	}

	@Test
	fun `actuator 기본 경로가 루트면 접두어로 가를 수 없어 아무것도 건너뛰지 않는다`() {
		// management.endpoints.web.base-path=/ 이면 WebEndpointProperties가 기본 경로를 빈 문자열로 돌려준다
		val outcome = send(filter(errorRate = 1.0, draw = 0.0, actuatorBasePath = ""), path = "/health")

		assertEquals(500, outcome.response.status)
	}

	@Test
	fun `예외로 에러 페이지에 다시 들어오는 ERROR 디스패치는 장애로 만들지 않는다`() {
		// 컨트롤러가 던진 진짜 예외가 /error로 넘어온 요청까지 한 번 더 장애로 만들면 같은 요청이 두 번 세어진다.
		// OncePerRequestFilter는 컨테이너가 ERROR 디스패치에 붙이는 jakarta.servlet.error.request_uri 속성으로 이를 알아본다
		val request = MockHttpServletRequest("GET", "/error").apply {
			dispatcherType = DispatcherType.ERROR
			setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/aB3xY9z")
		}
		val response = MockHttpServletResponse()
		val chain = MockFilterChain()

		filter(errorRate = 1.0, draw = 0.0).doFilter(request, response, chain)

		assertNotNull(chain.request, "필터가 요청을 넘겨야 한다")
		assertEquals(200, response.status)
		assertEquals(0.0, injectedCount())
	}
}
