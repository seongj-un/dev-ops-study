package com.devopsstudy.shortener

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * 통합 테스트 공통 설정.
 * 모든 통합 테스트가 같은 설정을 쓰면 Spring이 컨텍스트(와 Testcontainers 컨테이너)를 한 번만 띄워 재사용한다.
 * 테스트끼리 DB·Redis를 같이 쓰므로, 각 테스트는 고유한 데이터를 만들고 테이블이 비어 있다고 가정하지 않는다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
// base-url을 테스트에서 고정한다: 개발 셸이나 CI에 SHORTENER_BASE_URL이 export돼 있어도 단축 주소 검증이 깨지지 않게 한다
// 장애 주입 비율도 0으로 고정한다: 로컬 실험 때 export한 SHORTENER_FAULT_ERROR_RATE가 남아 있어도 테스트 요청이 무작위로 500이 되지 않게 한다 (켜진 상태는 fault 패키지의 테스트가 따로 만든다)
// 캐시 cooldown은 0으로 끈다: 테스트들이 UrlCache 빈 하나를 같이 쓰므로, 느린 CI에서 Redis 명령 하나가 타임아웃(200ms)을 넘기면 10초 동안 캐시가 꺼져
// 뒤따르는 캐시 테스트까지 줄줄이 깨진다. cooldown 동작은 UrlCacheCooldownTest·ShortUrlServiceRedisDownTest·DatabaseOutageTest가 따로 본다
@SpringBootTest(properties = ["shortener.base-url=http://localhost:8080", "shortener.fault.error-rate=0", "shortener.cache-cooldown=0s"])
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
