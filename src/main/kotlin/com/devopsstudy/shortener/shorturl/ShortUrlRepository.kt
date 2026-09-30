package com.devopsstudy.shortener.shorturl

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface ShortUrlRepository : JpaRepository<ShortUrl, Long> {
	fun findByCode(code: String): ShortUrl?

	/**
	 * 조회수 +1을 UPDATE 한 번으로 처리한다.
	 * "읽고 → 더하고 → 저장" 방식은 동시 요청이 서로의 값을 덮어써서 조회수가 유실된다.
	 * 리포지토리 기본 트랜잭션이 readOnly라서 쓰기 트랜잭션(@Transactional)을 따로 연다.
	 *
	 * @return 바뀐 행 수 (코드가 없으면 0)
	 */
	@Transactional
	@Modifying
	@Query("update ShortUrl s set s.clickCount = s.clickCount + 1 where s.code = :code")
	fun incrementClickCount(@Param("code") code: String): Int
}
