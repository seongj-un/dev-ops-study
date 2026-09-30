package com.devopsstudy.shortener.shorturl

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class RedirectController(
	private val service: ShortUrlService,
) {
	/**
	 * 302(Found)로 보낸다. 301(Moved Permanently)은 브라우저가 기억해 두고 다음부터 서버를 거치지 않아서
	 * 조회수를 셀 수 없고, 나중에 목적지를 바꿀 수도 없다.
	 *
	 * 경로 변수 정규식은 favicon.ico처럼 Base62 밖의 문자(점 등)가 든 경로를 코드로 착각하지 않게 걸러 낼 뿐이다.
	 * Actuator 경로가 이 매핑에 걸리지 않는 건 정규식 덕분이 아니라 아래 두 가지 이유 때문이다.
	 * - `/actuator`는 세그먼트가 하나라 정규식에 맞는다. 그래도 Actuator의 핸들러 매핑이 우선순위가 더 높아서 그쪽이 먼저 처리한다.
	 * - `/actuator/health` 같은 세그먼트가 둘 이상인 경로는 세그먼트 하나짜리 패턴에 애초에 맞지 않는다.
	 */
	@GetMapping("/{code:[0-9a-zA-Z]+}")
	fun redirect(@PathVariable code: String): ResponseEntity<Void> =
		ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(service.resolve(code)))
			.build()
}
