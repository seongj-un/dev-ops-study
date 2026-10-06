package com.devopsstudy.shortener

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * shortener.* 설정이 의도한 값으로 바인딩되고, 범위를 벗어난 장애 주입 비율은 앱이 뜨는 단계에서 막히는지 확인한다.
 * DB·Redis가 필요 없어서 ShortenerProperties만 올린 가벼운 컨텍스트로 돌린다 (컨테이너를 띄우지 않는다).
 */
class ShortenerPropertiesTest {
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ShortenerProperties::class)
	class PropertiesConfiguration

	// 시스템 환경 변수와 시스템 프로퍼티를 뺀다: 개발 셸이나 CI에 SHORTENER_FAULT_ERROR_RATE 같은 변수가 export돼 있어도 결과가 같게 한다
	private fun runner() = ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration::class.java)
		.withInitializer { context ->
			context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
			context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
		}

	/** application.yml을 그대로 읽는다. env는 시스템 환경 변수를 대신해 넣는 값이다. */
	private fun runnerWithApplicationYml(env: Map<String, String> = emptyMap()) = runner().withInitializer { context ->
		YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
			.forEach { context.environment.propertySources.addLast(it) }
		context.environment.propertySources.addFirst(MapPropertySource("env", env))
	}

	private fun AssertableApplicationContext.errorRate() = getBean(ShortenerProperties::class.java).fault.errorRate

	/** 시작 실패의 원인 사슬 전체의 메시지. 검증 메시지는 로케일에 따라 달라서, 로케일과 상관없는 필드 이름만 본다. */
	private fun AssertableApplicationContext.failureMessages(): String {
		val failure = assertNotNull(startupFailure, "앱이 뜨지 않아야 한다")
		return generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.message.orEmpty() }
	}

	@Test
	fun `장애 주입 비율의 기본값은 0이다`() {
		runner().run { assertEquals(0.0, it.errorRate()) }
	}

	@Test
	fun `application_yml은 환경 변수가 없으면 비율을 0으로 둔다`() {
		runnerWithApplicationYml().run { assertEquals(0.0, it.errorRate()) }
	}

	@Test
	fun `SHORTENER_FAULT_ERROR_RATE 환경 변수가 비율이 된다`() {
		// 차트(값 fault.errorRate)가 넘기는 변수 이름과 application.yml의 플레이스홀더가 어긋나지 않았는지 본다
		runnerWithApplicationYml(mapOf("SHORTENER_FAULT_ERROR_RATE" to "0.25")).run { assertEquals(0.25, it.errorRate()) }
	}

	@Test
	fun `차트의 기본값 0도 받아 준다`() {
		runnerWithApplicationYml(mapOf("SHORTENER_FAULT_ERROR_RATE" to "0")).run { assertEquals(0.0, it.errorRate()) }
	}

	@ParameterizedTest(name = "비율 {0}은 받아 준다")
	@ValueSource(strings = ["0", "0.0", "0.5", "1", "1.0"])
	fun `0과 1 사이의 비율은 경계값까지 받아 준다`(value: String) {
		runner().withPropertyValues("shortener.fault.error-rate=$value").run {
			assertNull(it.startupFailure)
			assertEquals(value.toDouble(), it.errorRate())
		}
	}

	@ParameterizedTest(name = "비율 {0}이면 앱이 뜨지 않는다")
	@ValueSource(strings = ["-0.1", "-1", "1.0001", "1.5", "2", "NaN", "Infinity", "-Infinity"])
	fun `범위를 벗어난 비율이면 시작 단계에서 실패한다`(value: String) {
		runner().withPropertyValues("shortener.fault.error-rate=$value").run {
			assertContains(it.failureMessages(), "fault.errorRate")
		}
	}

	@Test
	fun `환경 변수로 범위를 벗어난 비율을 넣어도 시작 단계에서 실패한다`() {
		// 환경 변수 → application.yml의 플레이스홀더 → 검증까지 이어서 확인한다
		runnerWithApplicationYml(mapOf("SHORTENER_FAULT_ERROR_RATE" to "1.5")).run {
			assertContains(it.failureMessages(), "fault.errorRate")
		}
		runnerWithApplicationYml(mapOf("SHORTENER_FAULT_ERROR_RATE" to "-0.1")).run {
			assertContains(it.failureMessages(), "fault.errorRate")
		}
	}

	@ParameterizedTest(name = "비율 \"{0}\"은 숫자가 아니라서 앱이 뜨지 않는다")
	@ValueSource(strings = ["abc", "50%", ""])
	fun `숫자가 아닌 비율이면 시작 단계에서 실패한다`(value: String) {
		runnerWithApplicationYml(mapOf("SHORTENER_FAULT_ERROR_RATE" to value)).run {
			assertContains(it.failureMessages(), "shortener.fault.error-rate")
		}
	}
}
