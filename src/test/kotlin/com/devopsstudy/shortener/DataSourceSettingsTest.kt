package com.devopsstudy.shortener

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals

/**
 * DB 접속 설정(타임아웃, 연결 재시도, 비밀번호 기본값)이 의도한 값으로 적용됐는지 확인한다.
 * 타임아웃은 DB가 멈추는 장애가 나야 효과가 드러나고 재시도는 DB가 앱보다 늦게 떠야 드러나서, 평소에는 값이 맞는지 알 수 없다. 게다가 Spring Boot는 설정 키를 잘못 적어도
 * (오타, 잘못된 경로) 모르는 키를 오류 없이 무시하고(@ConfigurationProperties의 기본 동작) 라이브러리 기본값으로 뜨므로, 값이 커넥션 풀과 Flyway에 실제로 들어갔는지를 여기서 못 박는다.
 */
@IntegrationTest
class DataSourceSettingsTest(
	@Autowired private val dataSource: HikariDataSource,
	@Autowired private val flyway: Flyway,
) {
	@Test
	fun `풀에서 커넥션을 기다리는 최대 시간은 1초다`() {
		// Hikari의 connectionTimeout은 밀리초 단위다 (기본값은 30000)
		assertEquals(1_000L, dataSource.connectionTimeout)
	}

	@Test
	fun `커넥션을 내주기 전 유효성 검사는 1초까지만 기다리고 놀고 있는 커넥션은 30초마다 확인한다`() {
		// Hikari의 validationTimeout(기본 5초)과 keepaliveTime(기본 0, 꺼짐)은 밀리초 단위다. 30초보다 짧은 keepaliveTime은 Hikari가 경고만 남기고 꺼 버리므로 값이 적용됐는지 확인해 둔다
		assertEquals(1_000L, dataSource.validationTimeout)
		assertEquals(30_000L, dataSource.keepaliveTime)
	}

	@Test
	fun `pgjdbc 연결 타임아웃 5초와 소켓 타임아웃 10초가 드라이버 속성으로 넘어간다`() {
		// pgjdbc 속성은 초 단위다. Hikari는 dataSourceProperties를 커넥션을 맺을 때 드라이버에 그대로 넘긴다
		assertEquals("5", dataSource.dataSourceProperties.getProperty("connectTimeout"))
		assertEquals("10", dataSource.dataSourceProperties.getProperty("socketTimeout"))
	}

	@Test
	fun `실제로 맺은 커넥션에 pgjdbc의 소켓 타임아웃 10초가 걸려 있다`() {
		// pgjdbc는 이름이 틀린 속성(대소문자 포함)을 오류 없이 무시한다. 그래서 속성 맵에 값이 들어 있다는 것만으로는 드라이버가 그 값을 썼다는 증거가 못 되고,
		// 실제로 맺은 커넥션에서 직접 읽어야 한다. JDBC의 networkTimeout은 밀리초 단위이고, pgjdbc는 소켓 읽기 타임아웃(socketTimeout)을 여기로 돌려준다
		dataSource.connection.use { connection ->
			assertEquals(10_000, connection.networkTimeout)
		}
	}

	@Test
	fun `Flyway는 DB에 연결하지 못하면 7번까지 다시 시도하고 대기 시간의 상한은 10초다`() {
		// Flyway의 기본값은 재시도 0번이라서, DB가 앱보다 늦게 뜨면 마이그레이션이 바로 실패해 앱이 죽는다. 두 값 모두 Flyway 설정에는 초 단위 정수로 들어간다
		assertEquals(7, flyway.configuration.connectRetries)
		assertEquals(10, flyway.configuration.connectRetriesInterval)
	}

	@Test
	fun `DB_PASSWORD 환경 변수가 없으면 비밀번호는 빈 문자열이다`() {
		// 개발용 비밀번호가 기본값으로 남아 있지 않은지 본다. 통합 테스트는 Testcontainers가 접속 정보를 직접 넣어 줘서 실행 중인 컨텍스트로는 확인할 수 없다.
		// 그래서 application.yml만 읽는다. MockEnvironment는 시스템 환경 변수를 보지 않으므로 개발 셸이나 CI에 DB_PASSWORD가 export돼 있어도 결과가 같다.
		assertEquals("", applicationYml().getProperty("spring.datasource.password"))
	}

	@Test
	fun `DB_PASSWORD 환경 변수가 있으면 그 값이 비밀번호가 된다`() {
		// compose와 Helm 차트가 넘기는 변수 이름과 application.yml의 플레이스홀더가 어긋나지 않았는지 본다
		val environment = applicationYml().withProperty("DB_PASSWORD", "주입된-비밀번호")

		assertEquals("주입된-비밀번호", environment.getProperty("spring.datasource.password"))
	}

	private fun applicationYml(): MockEnvironment {
		val environment = MockEnvironment()
		YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
			.forEach { environment.propertySources.addLast(it) }
		return environment
	}
}
