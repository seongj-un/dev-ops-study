package com.devopsstudy.shortener

import com.devopsstudy.shortener.fault.server500Count
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertEquals

/**
 * DB 장애 때의 동작을 실제 서버(임의 포트)와 진짜 PostgreSQL·Redis(Testcontainers)로 확인한다. 5단계 장애 훈련에서 드러난 문제를 고친 뒤의 모습이다.
 * - 캐시에 있는 리다이렉트는 계속 302다. 조회수 쓰기는 따로 돌다 실패해 shortener_clicks_dropped_total{reason="error"}로 센다.
 * - 캐시에 없는 리다이렉트는 앱이 500으로 답하고 http_server_requests에 status 500으로 남는다 (SLO가 이것을 본다).
 * - readiness는 200(UP)이라 파드가 Service 엔드포인트에서 빠지지 않는다. 구성요소별로 보는 전체 health는 503(DOWN)이다.
 * DB 장애는 테스트 DB에 새로 접속하지 못하게 막고(ALLOW_CONNECTIONS false) 이미 맺은 접속을 끊어서 만든다. 접속이 바로 거절되므로 Hikari 타임아웃(1초) 뒤 실패한다.
 * DB를 망가뜨리므로 다른 통합 테스트와 컨텍스트(와 컨테이너)를 같이 쓰지 않게, 서버를 띄우는 설정으로 따로 뜬다. 끝나면 접속을 다시 허용한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["shortener.base-url=http://localhost:8080", "shortener.fault.error-rate=0"],
)
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class)
class DatabaseOutageTest(
	@LocalServerPort private val port: Int,
	@Autowired private val postgres: PostgreSQLContainer,
) {
	private val jsonMapper = JsonMapper.builder().build()

	// HTTP/1.1로 고정한다: JDK 클라이언트는 기본으로 h2c 업그레이드를 먼저 시도한다
	private val client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build()

	@AfterEach
	fun tearDown() {
		allowConnections(true)
		client.close()
	}

	private fun send(path: String, method: String = "GET", jsonBody: String? = null): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
		if (jsonBody == null) {
			builder.method(method, BodyPublishers.noBody())
		} else {
			builder.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(jsonBody))
		}
		return client.send(builder.build(), BodyHandlers.ofString())
	}

	private fun create(url: String): String {
		val response = send("/api/v1/urls", "POST", """{"url": "$url"}""")
		assertEquals(201, response.statusCode(), response.body())
		return jsonMapper.readTree(response.body()).get("code").asString()
	}

	private fun prometheus(): String = send("/actuator/prometheus").body()

	/** 이름이 metric이고 레이블에 label이 든 시계열 값을 모두 더한다 */
	private fun String.sum(metric: String, label: String = ""): Double = lines()
		.filter { it.startsWith("$metric{") && it.contains(label) }
		.sumOf { it.substringAfterLast(' ').toDouble() }

	private fun clicksRecorded() = prometheus().sum("shortener_clicks_recorded_total")

	private fun clicksDroppedByError() = prometheus().sum("shortener_clicks_dropped_total", "reason=\"error\"")

	private fun cacheHits() = prometheus().sum("shortener_cache_requests_total", "result=\"hit\"")

	/** 테스트 DB가 아니라 관리용 postgres DB에 붙어 실행한다. 테스트 DB 접속을 끊어도 이 접속은 남는다. */
	private fun admin(sql: String) {
		val url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/postgres"
		DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
			connection.createStatement().use { it.execute(sql) }
		}
	}

	private fun allowConnections(allow: Boolean) {
		admin("alter database \"${postgres.databaseName}\" allow_connections $allow")
	}

	private fun breakDatabase() {
		allowConnections(false)
		// 두 번째 인자(ms)를 주면 백엔드가 실제로 끝날 때까지 기다린다(PostgreSQL 14+). 끊기 직전에 시작한 조회수 쓰기가 "장애" 뒤에 성공하지 않게 한다
		admin("select pg_terminate_backend(pid, 5000) from pg_stat_activity where datname = '${postgres.databaseName}'")
	}

	@Test
	fun `DB가 죽어도 캐시에 있는 리다이렉트는 302이고 캐시에 없으면 앱이 500으로 답한다`() {
		val cachedCode = create("https://example.com/db-outage-cached")
		val uncachedCode = create("https://example.com/db-outage-uncached")
		val recordedBefore = clicksRecorded()
		// 전제: 이 코드의 리다이렉트가 Redis 캐시에서 나온다. 첫 리다이렉트가 캐시에 넣고, 두 번째부터 적중한다.
		// 느린 환경에서 Redis 첫 연결이 타임아웃(200ms)을 넘기면 cooldown(10초) 동안 캐시를 건너뛰므로, 적중할 때까지 다시 보낸다
		var redirects = 0
		await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).until {
			val hitsBefore = cacheHits()
			assertEquals(302, send("/$cachedCode").statusCode())
			redirects++
			cacheHits() > hitsBefore
		}
		// 장애 전의 조회수 쓰기가 장애 중 실패로 세지지 않게, 다 써질 때까지 기다린다
		await().atMost(Duration.ofSeconds(5)).until { clicksRecorded() >= recordedBefore + redirects }
		val droppedBefore = clicksDroppedByError()
		val server500Before = prometheus().server500Count()

		breakDatabase()

		val cached = send("/$cachedCode")
		assertEquals(302, cached.statusCode(), cached.body())
		assertEquals("https://example.com/db-outage-cached", cached.headers().firstValue("Location").orElse(null))
		await().atMost(Duration.ofSeconds(10)).until { clicksDroppedByError() >= droppedBefore + 1 }

		assertEquals(500, send("/$uncachedCode").statusCode())
		await().atMost(Duration.ofSeconds(5)).until { prometheus().server500Count() >= server500Before + 1 }

		assertEquals(200, send("/actuator/health/readiness").statusCode(), "DB가 죽어도 파드는 트래픽에서 빠지지 않는다")
		assertEquals(200, send("/actuator/health/liveness").statusCode(), "DB 장애로 컨테이너를 재시작하지 않는다")
		assertEquals(503, send("/actuator/health").statusCode(), "전체 health에서는 DB 장애가 보인다")

		allowConnections(true)

		// DB가 돌아오면 Hikari가 새 커넥션을 맺어 캐시에 없던 리다이렉트도 다시 된다.
		// 장애 동안 Hikari의 새 연결 재시도 간격이 약 5초까지 늘어나고, 그동안 한 번 확인할 때마다 1초(커넥션 대기)가 걸리므로 넉넉히 기다린다
		await().atMost(Duration.ofSeconds(30)).untilAsserted { assertEquals(302, send("/$uncachedCode").statusCode()) }
	}
}
