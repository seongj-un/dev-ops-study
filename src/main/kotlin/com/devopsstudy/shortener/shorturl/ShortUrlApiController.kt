package com.devopsstudy.shortener.shorturl

import com.devopsstudy.shortener.ShortenerProperties
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/urls")
class ShortUrlApiController(
	private val service: ShortUrlService,
	private val properties: ShortenerProperties,
) {
	@PostMapping
	fun create(@Valid @RequestBody request: CreateShortUrlRequest): ResponseEntity<ShortUrlResponse> {
		val body = ShortUrlResponse.of(service.create(request.url), properties.baseUrl)
		return ResponseEntity.created(URI.create(body.shortUrl)).body(body)
	}

	@GetMapping("/{code}")
	fun get(@PathVariable code: String): ShortUrlResponse =
		ShortUrlResponse.of(service.get(code), properties.baseUrl)
}
