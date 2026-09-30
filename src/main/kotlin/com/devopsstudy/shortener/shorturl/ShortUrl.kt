package com.devopsstudy.shortener.shorturl

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

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

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = Instant.now()
		protected set
}
