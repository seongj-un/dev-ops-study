package com.devopsstudy.shortener.shorturl

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "short_url")
class ShortUrl(
	code: String,
	originalUrl: String,
) {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null
		protected set

	@Column(nullable = false, unique = true, length = 16)
	var code: String = code
		protected set

	@Column(name = "original_url", nullable = false, length = 2048)
	var originalUrl: String = originalUrl
		protected set

	/** 엔티티를 고쳐서 올리지 않고 ShortUrlRepository.incrementClickCount의 UPDATE로만 올린다. */
	@Column(name = "click_count", nullable = false)
	var clickCount: Long = 0
		protected set

	// PostgreSQL의 TIMESTAMPTZ는 마이크로초까지만 저장한다 (그 아래는 반올림된다). Linux의 Instant.now()는 나노초까지 있어서
	// 그대로 두면 생성 응답(메모리 값)과 이후 조회 응답(DB에서 읽은 값)의 createdAt이 달라진다. 그래서 미리 마이크로초로 잘라 둔다.
	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
		protected set
}
