package com.devopsstudy.shortener

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("shortener")
data class ShortenerProperties(
	/** 응답의 shortUrl 앞에 붙는 공개 주소. 배포 환경마다 다르므로 환경 변수로 받는다. */
	val baseUrl: String = "http://localhost:8080",
	/** 리다이렉트 캐시(Redis) 유효 시간 */
	val cacheTtl: Duration = Duration.ofHours(24),
)
