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
	 * 경로 변수 정규식은 favicon.ico처럼 점이 들어간 경로를 코드로 착각하지 않게 걸러 낼 뿐이다.
	 * "actuator"도 [0-9a-zA-Z]+에 맞으므로 /actuator를 막아 주는 건 아니다.
	 * /actuator/health 같은 하위 경로가 안전한 건 경로 세그먼트가 둘 이상이라 이 매핑과 맞지 않고, Actuator의 핸들러 매핑이 우선하기 때문이다.
	 */
	@GetMapping("/{code:[0-9a-zA-Z]+}")
	fun redirect(@PathVariable code: String): ResponseEntity<Void> =
		ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(service.resolve(code)))
			.build()
}
