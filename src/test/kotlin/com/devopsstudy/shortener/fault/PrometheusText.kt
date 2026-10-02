package com.devopsstudy.shortener.fault

/** /actuator/prometheus 출력(Prometheus 텍스트 형식)에서 장애 주입 테스트가 보는 값을 꺼낸다. */

/** status="500"인 http_server_requests_seconds_count 계열의 값을 모두 더한다 (uri·method 같은 다른 레이블은 가리지 않는다). */
internal fun String.server500Count(): Double = lines()
	.filter { it.startsWith("http_server_requests_seconds_count{") && it.contains("status=\"500\"") }
	.sumOf { it.substringAfterLast(' ').toDouble() }

/** shortener_fault_injected_total의 값. 필터가 만들어지면 장애가 나기 전에도 0으로 나온다. */
internal fun String.faultInjectedTotal(): Double = lines()
	.filter { it.startsWith("shortener_fault_injected_total{") }
	.sumOf { it.substringAfterLast(' ').toDouble() }
