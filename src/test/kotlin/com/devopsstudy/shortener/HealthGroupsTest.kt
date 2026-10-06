package com.devopsstudy.shortener

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * application.yml의 health 그룹 설정을 컨테이너 없이 확인한다: DB가 DOWN이어도 readiness와 liveness는 UP이어야 한다.
 * actuator의 health 자동 구성만 올리고, 진짜 DB·Redis 대신 이름이 db·redis인 가짜 health indicator를 DOWN으로 넣는다
 * (Boot는 빈 이름에서 HealthIndicator 접미사를 떼어 구성요소 이름을 짓는다. 진짜 DB의 구성요소 이름도 db다).
 * 실제 DB를 끊었을 때 /actuator/health/readiness가 200인지는 DatabaseOutageTest(Docker 필요)가 본다.
 */
class HealthGroupsTest {
	private val runner = ApplicationContextRunner()
		.withConfiguration(
			AutoConfigurations.of(
				ApplicationAvailabilityAutoConfiguration::class.java,
				HealthContributorAutoConfiguration::class.java,
				HealthContributorRegistryAutoConfiguration::class.java,
				HealthEndpointAutoConfiguration::class.java,
				AvailabilityHealthContributorAutoConfiguration::class.java,
				AvailabilityProbesAutoConfiguration::class.java,
			),
		)
		// 개발 셸이나 CI의 환경 변수가 결과를 바꾸지 않게 시스템 환경 변수와 프로퍼티를 빼고 application.yml만 읽는다
		.withInitializer { context ->
			context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
			context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
			YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
				.forEach { context.environment.propertySources.addLast(it) }
		}
		.withBean("db", HealthIndicator::class.java, { HealthIndicator { Health.down().withDetail("error", "db is down").build() } })
		.withBean("redis", HealthIndicator::class.java, { HealthIndicator { Health.down().withDetail("error", "redis is down").build() } })

	/** 앱이 다 뜬 상태(살아 있고 트래픽을 받을 준비가 됨)로 만든다. SpringApplication이 아니라서 이 이벤트를 아무도 내지 않는다. */
	private fun AssertableApplicationContext.started(): AssertableApplicationContext = apply {
		AvailabilityChangeEvent.publish(this, LivenessState.CORRECT)
		AvailabilityChangeEvent.publish(this, ReadinessState.ACCEPTING_TRAFFIC)
	}

	private fun AssertableApplicationContext.health(vararg path: String) = getBean(HealthEndpoint::class.java).let {
		if (path.isEmpty()) it.health() else it.healthForPath(*path)
	} as CompositeHealthDescriptor

	private fun CompositeHealthDescriptor.componentNames(): Set<String> = assertNotNull(components, "구성요소가 없다").keys

	@Test
	fun `DB가 DOWN이어도 readiness는 UP이고 구성요소는 readinessState뿐이다`() {
		runner.run { context ->
			val readiness = context.started().health("readiness")

			assertEquals(Status.UP, readiness.status)
			assertEquals(setOf("readinessState"), readiness.componentNames())
		}
	}

	@Test
	fun `DB가 DOWN이어도 liveness는 UP이다`() {
		runner.run { context ->
			val liveness = context.started().health("liveness")

			assertEquals(Status.UP, liveness.status)
			assertFalse("db" in liveness.componentNames())
		}
	}

	@Test
	fun `readiness와 liveness 그룹에는 db와 redis가 들어 있지 않다`() {
		runner.run { context ->
			val groups = context.getBean(HealthEndpointGroups::class.java)

			listOf("readiness", "liveness").forEach { name ->
				val group = assertNotNull(groups.get(name), "$name 그룹이 없다")
				assertTrue(group.isMember("${name}State"), "$name 그룹은 앱 자신의 상태를 본다")
				assertFalse(group.isMember("db"), "$name 그룹에 db가 있다")
				assertFalse(group.isMember("redis"), "$name 그룹에 redis가 있다")
			}
		}
	}

	@Test
	fun `전체 health에는 DB와 Redis가 나오고 둘이 DOWN이면 DOWN이다`() {
		// 프로브에 쓰지 않는 전체 health는 구성요소별 상태를 보는 곳이다. DB 장애는 여기서 보인다
		runner.run { context ->
			val health = context.started().health()

			assertEquals(Status.DOWN, health.status)
			assertTrue(health.componentNames().containsAll(setOf("db", "redis", "readinessState", "livenessState")), health.componentNames().toString())
		}
	}
}
