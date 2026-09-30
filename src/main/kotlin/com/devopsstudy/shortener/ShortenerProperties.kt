package com.devopsstudy.shortener

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("shortener")
data class ShortenerProperties(
	/** 리다이렉트 캐시(Redis) 유효 시간 */
	val cacheTtl: Duration = Duration.ofHours(24),
)
