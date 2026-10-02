package com.devopsstudy.shortener.fault

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator

@Configuration(proxyBeanMethods = false)
class FaultInjectionConfiguration {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 장애를 낼지 정하는 난수원. 테스트는 이 타입의 @Primary 빈을 따로 넣어서 장애를 낼지 말지를 직접 정한다.
	 * 호출한 스레드의 ThreadLocalRandom을 쓴다. 요청 스레드가 하나의 난수 생성기를 같이 쓰며 다투지 않고,
	 * ThreadLocalRandom 인스턴스를 빈으로 들고 있다가 다른 스레드에서 쓰는 잘못도 피한다 (그 인스턴스는 만든 스레드에서만 써야 한다).
	 * RandomGenerator의 추상 메서드는 nextLong 하나뿐이라 람다로 만들고, nextDouble()은 그 위에서 기본 구현이 만든다.
	 */
	@Bean
	fun faultRandom(): RandomGenerator = RandomGenerator { ThreadLocalRandom.current().nextLong() }

	@Bean
	fun faultInjectionFilter(
		properties: ShortenerProperties,
		random: RandomGenerator,
		webEndpointProperties: WebEndpointProperties,
		jsonMapper: JsonMapper,
		meterRegistry: MeterRegistry,
	): FilterRegistrationBean<FaultInjectionFilter> {
		val errorRate = properties.fault.errorRate
		val filter = FaultInjectionFilter(errorRate, random, webEndpointProperties.basePath, jsonMapper, meterRegistry)
		return FilterRegistrationBean(filter).apply {
			order = ORDER
			// 비율이 0.0이면 필터를 체인에 넣지 않는다. 평소 운영에서는 요청 경로에 장애 주입 코드가 아예 없다.
			isEnabled = errorRate > 0.0
			if (isEnabled) {
				log.atWarn().addKeyValue("errorRate", errorRate).log("fault injection enabled")
			}
		}
	}

	companion object {
		// ServerHttpObservationFilter의 순서(HIGHEST_PRECEDENCE + 1)보다 커야 한다. 바로 다음에 두어서, 다른 필터를 거치기 전에 끊되 관측에는 잡히게 한다.
		const val ORDER = Ordered.HIGHEST_PRECEDENCE + 2
	}
}
