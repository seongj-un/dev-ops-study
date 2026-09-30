package com.devopsstudy.shortener.shorturl

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateShortUrlRequest(
	@field:NotBlank
	@field:Size(max = ShortUrlService.MAX_URL_LENGTH)
	val url: String,
)

data class ShortUrlResponse(
	val code: String,
	val shortUrl: String,
	val originalUrl: String,
	val clickCount: Long,
	val createdAt: Instant,
) {
	companion object {
		fun of(shortUrl: ShortUrl, baseUrl: String) = ShortUrlResponse(
			code = shortUrl.code,
			shortUrl = "${baseUrl.trimEnd('/')}/${shortUrl.code}",
			originalUrl = shortUrl.originalUrl,
			clickCount = shortUrl.clickCount,
			createdAt = shortUrl.createdAt,
		)
	}
}
