package com.devopsstudy.shortener.shorturl

import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 컨테이너 없이 쓰는 가짜 Redis. 값은 메모리에 두고, down을 켜면 모든 명령이 연결 실패(RedisConnectionFailureException)로 끝난다.
 * calls는 실제로 Redis까지 간 명령(get·set) 수다. UrlCache가 캐시를 건너뛰면 늘지 않는다.
 * beforeCommand는 명령마다 먼저 불린다. 테스트가 명령을 붙잡아 두어 "Redis를 기다리는 중"인 요청을 만들 때 쓴다.
 */
class FakeRedis {
	val store = ConcurrentHashMap<String, String>()

	@Volatile
	var down = false

	@Volatile
	var beforeCommand: () -> Unit = {}

	val calls = AtomicInteger()

	@Suppress("UNCHECKED_CAST")
	private val operations = Mockito.mock(
		ValueOperations::class.java,
		Answer { invocation ->
			when (invocation.method.name) {
				"get" -> command { store[invocation.arguments[0] as String] }
				// UrlCache는 TTL(Duration)을 주는 set(key, value, Duration)을 쓴다
				"set" -> command { store[invocation.arguments[0] as String] = invocation.arguments[1] as String; null }
				else -> error("가짜 Redis가 모르는 명령이다: ${invocation.method}")
			}
		},
	) as ValueOperations<String, String>

	/**
	 * UrlCache에 넘길 템플릿. 연결 팩토리 없이 opsForValue()만 가짜로 바꿔 끼운다.
	 * 스프링 빈으로 넣으면 afterPropertiesSet()이 연결 팩토리를 요구하므로 그것도 비워 둔다.
	 */
	val template: StringRedisTemplate = object : StringRedisTemplate() {
		override fun opsForValue(): ValueOperations<String, String> = operations

		override fun afterPropertiesSet() = Unit
	}

	private fun command(block: () -> String?): String? {
		calls.incrementAndGet()
		beforeCommand()
		if (down) throw RedisConnectionFailureException("fake redis is down")
		return block()
	}
}
