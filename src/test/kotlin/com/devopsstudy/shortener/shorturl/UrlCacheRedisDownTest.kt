package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 아무것도 듣고 있지 않은 포트(1번)에 연결시켜 "Redis 장애" 상황을 만든다. 모킹 없이 실제 연결 실패를 쓴다. */
class UrlCacheRedisDownTest {
	// 운영처럼 타임아웃을 짧게 둔다: 연결을 거절하지 않고 패킷을 버리는 호스트에서도 테스트가 오래 멈추지 않게 한다.
	// localhost 대신 IPv4 주소를 써서 ::1 등으로 풀리는 경우를 없앤다.
	private val clientConfiguration = LettuceClientConfiguration.builder()
		.commandTimeout(Duration.ofMillis(200))
		.clientOptions(
			ClientOptions.builder()
				.socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
				.build(),
		)
		.build()
	private val connectionFactory =
		LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration).apply {
			afterPropertiesSet()
			start()
		}
	private val meterRegistry = SimpleMeterRegistry()
	private val cache = UrlCache(StringRedisTemplate(connectionFactory), ShortenerProperties(), meterRegistry)

	@AfterEach
	fun tearDown() {
		connectionFactory.destroy()
	}

	private fun errorCount() = meterRegistry.counter(UrlCache.METRIC, "result", "error").count()

	@Test
	fun `Redis에 연결할 수 없으면 get은 예외 대신 null을 돌려준다`() {
		assertNull(cache.get("abc1234"))
		assertEquals(1.0, errorCount())
	}

	@Test
	fun `Redis에 연결할 수 없어도 put은 예외를 던지지 않는다`() {
		cache.put("abc1234", "https://example.com")
		assertEquals(1.0, errorCount())
	}
}
