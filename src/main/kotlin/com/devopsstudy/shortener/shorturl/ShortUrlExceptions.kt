package com.devopsstudy.shortener.shorturl

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

// ErrorResponseException을 상속하면 Spring MVC가 알아서 RFC 9457 ProblemDetail(JSON) 응답으로 바꿔 준다.

class ShortUrlNotFoundException(code: String) : ErrorResponseException(
	HttpStatus.NOT_FOUND,
	ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "short url not found: $code"),
	null,
)

class InvalidUrlException(url: String) : ErrorResponseException(
	HttpStatus.BAD_REQUEST,
	ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "only absolute http(s) URLs are allowed: $url"),
	null,
)
