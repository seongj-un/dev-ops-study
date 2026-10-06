package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 스프링이 리다이렉트 경로의 빈을 실제 앱과 같은 방법으로 엮는지 컨테이너 없이 확인한다. DB·Redis 대신 가짜를 빈으로 넣는다.
 * - UrlCache는 생성자의 TimeSource 매개변수에 기본값이 있다. 스프링에는 TimeSource 빈이 없으므로 기본값으로 만들어져야 한다.
 * - ClickRecorder는 @Bean으로 만들어지고 AutoCloseable이라, 컨텍스트를 닫을 때 스프링이 close()를 불러 대기열을 비워야 한다.
 */
class ShortUrlWiringTest {
	private val redis = FakeRedis()
	private val incremented = ConcurrentLinkedQueue<String>()
	private val repository = Mockito.mock(
		ShortUrlRepository::class.java,
		Answer { invocation ->
			when (invocation.method.name) {
				"incrementClickCount" -> 1.also { incremented += invocation.arguments[0] as String }
				else -> error("가짜 리포지토리가 모르는 호출이다: ${invocation.method}")
			}
		},
	)

	private val runner = ApplicationContextRunner()
		.withUserConfiguration(ClickRecorderConfiguration::class.java)
		.withBean(ShortenerProperties::class.java, { ShortenerProperties() })
		.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
		.withBean(StringRedisTemplate::class.java, { redis.template })
		.withBean(ShortUrlRepository::class.java, { repository })
		.withBean(ShortCodeGenerator::class.java, { ShortCodeGenerator { error("리다이렉트는 코드를 만들지 않는다") } })
		.withBean(UrlCache::class.java)
		.withBean(ShortUrlService::class.java)

	@Test
	fun `스프링이 빈을 엮어 리다이렉트하고, 컨텍스트를 닫을 때 남은 조회수를 마저 쓴다`() {
		redis.store["${UrlCache.KEY_PREFIX}abc1234"] = "https://example.com/wired"

		runner.run { context ->
			assertNull(context.startupFailure)
			assertEquals("https://example.com/wired", context.getBean(ShortUrlService::class.java).resolve("abc1234"))
		}

		// run이 끝나면 컨텍스트가 닫히고, 그때 ClickRecorder.close()가 대기열의 쓰기를 끝낼 때까지 기다린다
		assertEquals(listOf("abc1234"), incremented.toList())
	}
}
