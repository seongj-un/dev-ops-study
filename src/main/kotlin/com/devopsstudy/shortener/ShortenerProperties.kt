package com.devopsstudy.shortener

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

// @Validated: 아래 제약 조건(@DecimalMin 등)을 앱이 뜰 때 검사한다. 범위를 벗어난 값이면 앱이 시작 단계에서 죽는다.
@Validated
@ConfigurationProperties("shortener")
data class ShortenerProperties(
	/** 응답의 shortUrl 앞에 붙는 공개 주소. 배포 환경마다 다르므로 환경 변수로 받는다. */
	val baseUrl: String = "http://localhost:8080",
	/** 리다이렉트 캐시(Redis) 유효 시간 */
	val cacheTtl: Duration = Duration.ofHours(24),
	/** 훈련용 장애 주입. 중첩 객체의 제약 조건까지 검사하려면 @Valid가 있어야 한다. */
	@field:Valid val fault: Fault = Fault(),
) {
	data class Fault(
		/**
		 * 앱 포트로 들어온 요청 중 HTTP 500을 바로 돌려줄 비율(0.0~1.0). 0.0이면 꺼진 것이다.
		 * 카나리 롤백과 알림 훈련에서 "나쁜 버전"을 흉내 내는 데만 쓰고, 평소 운영에서는 켜지 않는다.
		 */
		@field:DecimalMin("0.0")
		@field:DecimalMax("1.0")
		val errorRate: Double = 0.0,
	)
}
