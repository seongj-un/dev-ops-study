package com.devopsstudy.shortener

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
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
	/** Redis 호출이 실패한 뒤 Redis를 부르지 않고 바로 DB로 가는 시간 (UrlCache). 값을 고른 이유는 application.yml에 있다. */
	val cacheCooldown: Duration = Duration.ofSeconds(10),
	/** 조회수를 요청 스레드 밖에서 쓰는 ClickRecorder의 크기. 중첩 객체의 제약 조건까지 검사하려면 @Valid가 있어야 한다. */
	@field:Valid val clicks: Clicks = Clicks(),
	/** 훈련용 장애 주입. 중첩 객체의 제약 조건까지 검사하려면 @Valid가 있어야 한다. */
	@field:Valid val fault: Fault = Fault(),
) {
	data class Clicks(
		/** 조회수를 DB에 쓰는 스레드 수. 조회수 쓰기가 동시에 쥐는 DB 커넥션 수의 상한이기도 하다. */
		@field:Min(1)
		val threads: Int = 2,
		/** 쓰기를 기다리는 조회수의 최대 개수. 차면 새 조회수는 버린다. */
		@field:Min(1)
		val queueCapacity: Int = 1000,
		/** 앱이 종료될 때 대기열에 남은 쓰기를 기다리는 최대 시간. 지나면 남은 것은 버린다. */
		val shutdownTimeout: Duration = Duration.ofSeconds(3),
	)

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
