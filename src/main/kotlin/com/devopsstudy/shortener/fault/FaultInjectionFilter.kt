package com.devopsstudy.shortener.fault

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.util.random.RandomGenerator

/**
 * 훈련용 장애 주입 필터. 앱 포트로 들어온 요청 중 errorRate 비율만큼을 컨트롤러까지 보내지 않고 HTTP 500으로 끊는다.
 * 카나리 분석(5xx 비율)과 SLO 알림 훈련에서 "나쁜 버전"을 흉내 내려는 스위치라서, 비율이 0.0인 평소에는 이 필터를 체인에 넣지도 않는다(FaultInjectionConfiguration).
 *
 * 필터 순서: ServerHttpObservationFilter(HIGHEST_PRECEDENCE + 1)보다 뒤에 있어야 한다. 앞에 있으면 우리가 끊은 요청은 관측 필터를 거치지 않아
 * http.server.requests에 남지 않고, 카나리 분석과 SLO가 읽는 5xx 비율에도 잡히지 않는다. 순서는 FaultInjectionTest가 /actuator/prometheus로 확인한다.
 * 끊은 요청은 컨트롤러에 닿지 않아서 uri 레이블이 경로 패턴이 아니라 UNKNOWN으로 잡힌다. SLO 규칙과 대시보드는 uri!~"/actuator.*"로만 거르므로 그대로 센다.
 *
 * 관리 포트: 포트를 따로 둔 actuator(쿠버네티스에서는 8081)는 Boot가 자식 컨텍스트와 별도 서블릿 컨테이너로 띄우고, 거기에는 앱 컨텍스트의 필터가 등록되지 않는다.
 * 그래서 이 필터를 거치지 않는다(FaultInjectionManagementPortTest). 앱 포트와 합친 경우(로컬 기본)에는 actuator 경로를 직접 건너뛴다.
 * 프로브까지 500이면 쿠버네티스가 파드를 재시작해서, 5xx 비율을 보려던 분석에 데이터가 남지 않는다.
 */
class FaultInjectionFilter(
	private val errorRate: Double,
	private val random: RandomGenerator,
	private val actuatorBasePath: String,
	private val jsonMapper: JsonMapper,
	meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
	private val log = LoggerFactory.getLogger(javaClass)
	private val injected = meterRegistry.counter(METRIC)

	// 비교를 > 로 써서 NaN도 꺼진 것으로 본다 (설정 검증이 NaN을 막지만, 어긋나도 모든 요청을 장애로 만드는 쪽으로는 기울지 않게 한다)
	private val enabled = errorRate > 0.0

	// OncePerRequestFilter는 ERROR·ASYNC 디스패치를 기본으로 건너뛴다. 예외가 나서 /error로 다시 들어오는 요청을 한 번 더 장애로 만들지 않는다.
	override fun shouldNotFilter(request: HttpServletRequest): Boolean = !enabled || isActuator(request)

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
		// nextDouble()은 [0.0, 1.0)이라서 비율 1.0이면 늘, 0.0이면 한 번도 장애가 나지 않는다
		if (random.nextDouble() < errorRate) {
			injectFault(request, response)
		} else {
			filterChain.doFilter(request, response)
		}
	}

	private fun injectFault(request: HttpServletRequest, response: HttpServletResponse) {
		injected.increment()
		log.atWarn().addKeyValue("method", request.method).addKeyValue("path", request.requestURI).log("fault injected")
		// 앱의 다른 에러 응답(400·404)과 같은 RFC 9457 형식(application/problem+json)으로 맞춘다
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "fault injected by shortener.fault.error-rate")
		problem.instance = instanceOf(request)
		// sendError가 아니라 상태와 본문을 직접 쓴다. sendError는 컨테이너가 /error로 다시 디스패치해서 Boot 기본 에러 JSON으로 본문을 바꿔 버린다.
		response.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
		response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		response.outputStream.write(jsonMapper.writeValueAsBytes(problem))
	}

	// URI로 해석되지 않는 경로(예: "//")로 온 요청이어도 장애 응답을 만들다 예외가 나지 않게, 그때는 instance를 비워 둔다
	private fun instanceOf(request: HttpServletRequest): URI? =
		try {
			URI(request.requestURI)
		} catch (e: URISyntaxException) {
			null
		}

	// 기본 경로(/actuator)와 그 아래만 건너뛴다. "/actuatorx"는 단축 코드로 쓸 수 있는 경로라 장애 대상이다.
	// 기본 경로를 /로 옮기면(management.endpoints.web.base-path=/) 접두어로 가를 수 없어서 아무것도 건너뛰지 않는다.
	private fun isActuator(request: HttpServletRequest): Boolean {
		if (actuatorBasePath.isEmpty()) return false
		val path = request.requestURI.removePrefix(request.contextPath)
		return path == actuatorBasePath || path.startsWith("$actuatorBasePath/")
	}

	companion object {
		// 이 이름을 카나리 분석과 알림 훈련이 쿼리로 쓴다 (Prometheus에서는 shortener_fault_injected_total)
		const val METRIC = "shortener.fault.injected"
	}
}
