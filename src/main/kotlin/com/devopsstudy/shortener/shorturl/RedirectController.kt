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
	 * 경로 변수를 Base62 문자로 제한해 /actuator 같은 다른 경로나 favicon.ico와 겹치지 않게 한다.
	 */
	@GetMapping("/{code:[0-9a-zA-Z]+}")
	fun redirect(@PathVariable code: String): ResponseEntity<Void> =
		ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(service.resolve(code)))
			.build()
}
