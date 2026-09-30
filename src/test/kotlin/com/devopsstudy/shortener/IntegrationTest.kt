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
@SpringBootTest(properties = ["shortener.base-url=http://localhost:8080"])
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
