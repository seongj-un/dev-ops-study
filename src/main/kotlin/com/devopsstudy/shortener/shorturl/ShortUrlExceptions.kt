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

// 입력한 URL을 detail에 되돌려 주지 않는다: 응답과 로그에 사용자 입력(자격 증명이 든 URL 등)이 그대로 남지 않게 한다.
class InvalidUrlException : ErrorResponseException(
	HttpStatus.BAD_REQUEST,
	ProblemDetail.forStatusAndDetail(
		HttpStatus.BAD_REQUEST,
		"url must be an absolute http(s) URL without user info, at most ${ShortUrlService.MAX_URL_LENGTH} characters",
	),
	null,
)
