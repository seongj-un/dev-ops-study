package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** ClickRecorder를 설정 값(shortener.clicks)으로 만든다. 테스트는 실행기와 쓰기 함수를 직접 넘겨 ClickRecorder를 만든다. */
@Configuration(proxyBeanMethods = false)
class ClickRecorderConfiguration {
	// ClickRecorder가 AutoCloseable이라 스프링이 컨텍스트를 닫을 때 close()를 불러 남은 쓰기를 마저 끝낸다
	@Bean
	fun clickRecorder(
		repository: ShortUrlRepository,
		properties: ShortenerProperties,
		meterRegistry: MeterRegistry,
	): ClickRecorder {
		val clicks = properties.clicks
		return ClickRecorder(
			executor = ClickRecorder.boundedExecutor(clicks.threads, clicks.queueCapacity),
			write = { code -> repository.incrementClickCount(code) },
			meterRegistry = meterRegistry,
			shutdownTimeout = clicks.shutdownTimeout,
		)
	}
}
