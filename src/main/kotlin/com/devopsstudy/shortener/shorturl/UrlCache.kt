package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 리다이렉트 조회용 Redis 캐시 (cache-aside: 없으면 호출한 쪽이 DB에서 읽어 put 한다).
 * 캐시는 "있으면 빠른 것"일 뿐이라, Redis가 죽어도 예외를 밖으로 던지지 않는다.
 * 실패는 로그와 메트릭(result=error)으로 남기고 null을 돌려줘서 DB로 넘어가게 한다.
 */
@Component
class UrlCache(
	private val redis: StringRedisTemplate,
	private val properties: ShortenerProperties,
	meterRegistry: MeterRegistry,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val hits = meterRegistry.counter(METRIC, "result", "hit")
	private val misses = meterRegistry.counter(METRIC, "result", "miss")
	private val errors = meterRegistry.counter(METRIC, "result", "error")

	fun get(code: String): String? =
		try {
			redis.opsForValue().get(key(code))
				.also { if (it == null) misses.increment() else hits.increment() }
		} catch (e: DataAccessException) {
			errors.increment()
			// 장애 중에는 요청마다 찍히므로 스택트레이스 없이 원인 메시지만 남긴다
			log.atWarn().addKeyValue("code", code).addKeyValue("error", e.message)
				.log("redis get failed, falling back to db")
			null
		}

	fun put(code: String, originalUrl: String) {
		try {
			redis.opsForValue().set(key(code), originalUrl, properties.cacheTtl)
		} catch (e: DataAccessException) {
			errors.increment()
			log.atWarn().addKeyValue("code", code).addKeyValue("error", e.message)
				.log("redis put failed")
		}
	}

	private fun key(code: String) = "$KEY_PREFIX$code"

	companion object {
		const val KEY_PREFIX = "short-url:"
		const val METRIC = "shortener.cache.requests"
	}
}
