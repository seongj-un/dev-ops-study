package com.devopsstudy.shortener.fault

import com.devopsstudy.shortener.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 쿠버네티스와 같은 배치를 확인한다: 서버를 실제로 띄우고 actuator를 앱과 다른 포트로 옮긴다 (배포 환경은 MANAGEMENT_SERVER_PORT=8081).
 * 비율을 1.0으로 두어 앱 포트의 요청이 전부 장애여도, 관리 포트의 프로브와 메트릭 수집은 영향이 없는지 본다.
 * startup·liveness·readiness 프로브가 모두 관리 포트를 본다. 실패하면 startup·liveness는 컨테이너 재시작으로, readiness는 파드가 Service 엔드포인트에서 빠져
 * 트래픽이 끊기는 것으로 이어지고, 어느 쪽이든 카나리 분석이 봐야 할 5xx가 보이지 않는다.
 * MockMvc는 서버가 하나라서 이 배치를 흉내 낼 수 없다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["management.server.port=0", "shortener.fault.error-rate=1.0"],
)
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class)
class FaultInjectionManagementPortTest(
	@LocalServerPort private val appPort: Int,
	@LocalManagementPort private val managementPort: Int,
) {
	private val jsonMapper = JsonMapper.builder().build()

	// HTTP/1.1로 고정한다: JDK 클라이언트는 기본으로 h2c 업그레이드를 먼저 시도한다
	private val client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build()

	@AfterEach
	fun closeClient() {
		client.close()
	}

	private fun request(port: Int, path: String, method: String = "GET", jsonBody: String? = null): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
		if (jsonBody == null) {
			builder.method(method, BodyPublishers.noBody())
		} else {
			builder.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(jsonBody))
		}
		return client.send(builder.build(), BodyHandlers.ofString())
	}

	private fun app(path: String, method: String = "GET", jsonBody: String? = null) = request(appPort, path, method, jsonBody)

	private fun management(path: String) = request(managementPort, path)

	@Test
	fun `관리 포트는 앱 포트와 다르다`() {
		assertTrue(managementPort != appPort, "management.server.port=0이면 앱과 다른 임의의 포트가 잡혀야 한다")
	}

	@Test
	fun `비율이 1이면 앱 포트의 요청은 모두 problem+json 500을 받는다`() {
		listOf(
			app("/api/v1/urls/nosuch1"),
			app("/nosuch2"),
			app("/api/v1/urls", "POST", """{"url": "https://example.com/management-port"}"""),
		).forEach { response ->
			assertEquals(500, response.statusCode(), response.body())
			assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(null))
			assertEquals(500, jsonMapper.readTree(response.body()).get("status").asInt())
		}
	}

	@Test
	fun `앱 포트가 전부 장애여도 관리 포트의 프로브와 메트릭 수집은 성공한다`() {
		// 먼저 장애를 내서 필터가 실제로 요청을 끊고 있는 상태에서 확인한다
		assertEquals(500, app("/api/v1/urls/nosuch1").statusCode())

		listOf(
			"/actuator/health/liveness",
			"/actuator/health/readiness",
			"/actuator/health",
			"/actuator/info",
			"/actuator/prometheus",
		).forEach { path -> assertEquals(200, management(path).statusCode(), "관리 포트의 $path") }
	}

	@Test
	fun `주입한 500은 관리 포트의 prometheus에서 status 500으로 보이고 카운터가 오른다`() {
		// Prometheus가 수집하는 경로(관리 포트의 /actuator/prometheus)에서 본다. 필터가 관측 필터보다 앞에 있으면 이 계열이 늘지 않는다
		val before = management("/actuator/prometheus").body()

		repeat(3) { assertEquals(500, app("/api/v1/urls/nosuch1").statusCode()) }

		val after = management("/actuator/prometheus").body()
		assertEquals(before.server500Count() + 3, after.server500Count())
		assertEquals(before.faultInjectedTotal() + 3, after.faultInjectedTotal())
	}
}
