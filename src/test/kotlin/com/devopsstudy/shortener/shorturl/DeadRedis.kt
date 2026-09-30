package com.devopsstudy.shortener.shorturl

import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Duration

/**
 * 아무것도 듣고 있지 않은 포트(1번)에 붙는 Redis 연결 팩토리. 모킹 없이 실제 연결 실패로 "Redis 장애"를 만든다.
 * 운영처럼 타임아웃을 짧게 둔다: 연결을 거절하지 않고 패킷을 버리는 호스트에서도 테스트가 오래 멈추지 않게 한다.
 * localhost 대신 IPv4 주소를 써서 ::1 등으로 풀리는 경우를 없앤다. 다 쓴 뒤에는 destroy()로 닫는다.
 */
fun deadRedisConnectionFactory(): LettuceConnectionFactory {
	val clientConfiguration = LettuceClientConfiguration.builder()
		.commandTimeout(Duration.ofMillis(200))
		.clientOptions(
			ClientOptions.builder()
				.socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
				.build(),
		)
		.build()
	return LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", 1), clientConfiguration).apply {
		afterPropertiesSet()
		start()
	}
}
