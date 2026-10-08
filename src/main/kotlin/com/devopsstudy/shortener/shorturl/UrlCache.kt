package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import kotlin.time.TimeSource

/**
 * 리다이렉트 조회용 Redis 캐시 (cache-aside: 없으면 호출한 쪽이 DB에서 읽어 put 한다).
 * 캐시는 "있으면 빠른 것"일 뿐이라, Redis가 죽어도 예외를 밖으로 던지지 않는다.
 * 실패는 로그와 메트릭(result=error)으로 남기고 null을 돌려줘서 DB로 넘어가게 한다.
 *
 * 한 번 실패하면 shortener.cache-cooldown 동안 Redis를 부르지 않는다(Cooldown). 그동안 get은 바로 null을 돌려주고 result=skipped로 세며,
 * put은 아무것도 하지 않는다 (성공한 put도 세지 않으므로 건너뛴 put도 세지 않는다). Redis가 응답 없이 멈췄을 때 요청마다 타임아웃을 기다리지 않게 하려는 것이다.
 * 경고 로그도 그 창마다 한 줄만 남기고, 그사이 더 난 실패 수를 suppressed로 함께 남긴다.
 */
@Component
class UrlCache(
	private val redis: StringRedisTemplate,
	private val properties: ShortenerProperties,
	meterRegistry: MeterRegistry,
	// 스프링에는 TimeSource 빈이 없어서 기본값(실제 단조 시계)이 쓰인다. 테스트는 TestTimeSource를 넘겨 시간을 직접 움직인다
	timeSource: TimeSource = TimeSource.Monotonic,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val hits = meterRegistry.counter(METRIC, "result", "hit")
	private val misses = meterRegistry.counter(METRIC, "result", "miss")
	private val errors = meterRegistry.counter(METRIC, "result", "error")
	private val skipped = meterRegistry.counter(METRIC, "result", "skipped")
	private val cooldown = Cooldown(properties.cacheCooldown, timeSource)
	private val warnings = LogThrottle(properties.cacheCooldown, timeSource)

	fun get(code: String): String? {
		// 시작 시각은 shouldSkip()보다 먼저 잰다. 그래야 이 호출이 건너뛸지 정하는 사이에 난 실패가 연 창을 이 호출의 늦은 성공이 닫지 못한다
		val startedAt = cooldown.mark()
		if (cooldown.shouldSkip()) {
			skipped.increment()
			return null
		}
		return try {
			redis.opsForValue().get(key(code))
				.also {
					if (it == null) misses.increment() else hits.increment()
					cooldown.onSuccess(startedAt)
				}
		} catch (e: DataAccessException) {
			fail(code, e, "redis get failed, falling back to db")
			null
		}
	}

	fun put(code: String, originalUrl: String) {
		val startedAt = cooldown.mark()
		if (cooldown.shouldSkip()) return
		try {
			redis.opsForValue().set(key(code), originalUrl, properties.cacheTtl)
			cooldown.onSuccess(startedAt)
		} catch (e: DataAccessException) {
			fail(code, e, "redis put failed")
		}
	}

	private fun fail(code: String, e: DataAccessException, message: String) {
		errors.increment()
		cooldown.onFailure()
		// 장애 중에는 반복되므로 스택트레이스 없이 원인 메시지만 남긴다
		val suppressed = warnings.acquire() ?: return
		log.atWarn().addKeyValue("code", code).addKeyValue("error", e.message)
			.addKeyValue("cooldown", properties.cacheCooldown.toString()).addKeyValue("suppressed", suppressed)
			.log(message)
	}

	private fun key(code: String) = "$KEY_PREFIX$code"

	companion object {
		const val KEY_PREFIX = "short-url:"
		const val METRIC = "shortener.cache.requests"
	}
}
