package com.devopsstudy.shortener.fault

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties
import org.springframework.core.Ordered
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 필터를 서블릿 체인에 등록하는 쪽(켜고 끄기, 순서, 시작 로그, 기본 난수원)을 스프링 컨텍스트 없이 확인한다. */
class FaultInjectionConfigurationTest {
	private val configuration = FaultInjectionConfiguration()
	private val logger = LoggerFactory.getLogger(FaultInjectionConfiguration::class.java) as Logger
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

	private fun registration(errorRate: Double) = configuration.faultInjectionFilter(
		ShortenerProperties(fault = ShortenerProperties.Fault(errorRate)),
		configuration.faultRandom(),
		WebEndpointProperties(),
		JsonMapper.builder().build(),
		SimpleMeterRegistry(),
	)

	@Test
	fun `비율이 0이면 필터를 체인에 넣지 않고 로그도 남기지 않는다`() {
		val registration = registration(errorRate = 0.0)

		assertFalse(registration.isEnabled)
		assertEquals(0, logEvents.list.size)
	}

	@Test
	fun `비율이 0보다 크면 필터를 체인에 넣고 시작할 때 WARN 로그를 한 줄 남긴다`() {
		val registration = registration(errorRate = 0.25)

		assertTrue(registration.isEnabled)
		val event = logEvents.list.single()
		assertEquals(Level.WARN, event.level)
		assertEquals("fault injection enabled", event.formattedMessage)
		assertEquals(mapOf("errorRate" to 0.25), event.keyValuePairs.associate { it.key to it.value })
	}

	@Test
	fun `필터는 관측 필터보다 뒤에 있다`() {
		// ServerHttpObservationFilter는 HIGHEST_PRECEDENCE + 1에 등록된다. 이 필터가 같거나 앞서면 끊은 요청이 http.server.requests에 남지 않는다.
		// 실제 체인에서 500이 기록되는지는 FaultInjectionTest가 본다
		assertTrue(registration(errorRate = 0.5).order > Ordered.HIGHEST_PRECEDENCE + 1)
	}

	@Test
	fun `기본 난수원은 0 이상 1 미만의 값을 낸다`() {
		// 필터의 판정(난수 < 비율)은 이 범위를 전제로 한다: 비율 1.0이면 늘, 0.0이면 한 번도 장애가 나지 않는다
		val random = configuration.faultRandom()

		val draws = List(10_000) { random.nextDouble() }

		assertTrue(draws.all { it >= 0.0 && it < 1.0 })
	}
}
