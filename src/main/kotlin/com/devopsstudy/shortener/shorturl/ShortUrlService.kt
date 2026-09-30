package com.devopsstudy.shortener.shorturl

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URISyntaxException

@Service
class ShortUrlService(
	private val repository: ShortUrlRepository,
	private val codeGenerator: ShortCodeGenerator,
	private val cache: UrlCache,
	meterRegistry: MeterRegistry,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	// 이름이 created로 끝나면 안 된다: Prometheus(OpenMetrics)는 _created를 예약 접미사로 써서 이 카운터를 shortener_urls_total로 내보낸다
	private val shortenedCounter = meterRegistry.counter("shortener.urls.shortened")
	private val redirectCounter = meterRegistry.counter("shortener.redirects")

	/**
	 * 원본 URL에 새 단축 코드를 붙여 저장한다.
	 * 코드 중복은 미리 조회해서 막지 않고 DB 유니크 제약에 맡긴다. 조회 → 저장 사이에 다른 요청이 끼어들 수 있기 때문이다.
	 * @Transactional을 붙이지 않는다: 한 트랜잭션 안에서 제약 위반이 나면 그 트랜잭션은 롤백만 가능해져서 재시도할 수 없다.
	 */
	fun create(originalUrl: String): ShortUrl {
		requireValidUrl(originalUrl)
		repeat(MAX_ATTEMPTS) { attempt ->
			val code = codeGenerator.generate()
			try {
				val saved = repository.saveAndFlush(ShortUrl(code, originalUrl))
				shortenedCounter.increment()
				log.atInfo().addKeyValue("code", code).log("short url created")
				return saved
			} catch (e: DataIntegrityViolationException) {
				log.atWarn().addKeyValue("code", code).addKeyValue("attempt", attempt + 1)
					.log("short code collision, retrying")
			}
		}
		throw IllegalStateException("could not generate a unique short code after $MAX_ATTEMPTS attempts")
	}

	/** 원본 URL을 돌려주고 조회수를 1 올린다. 원본 URL은 Redis에서 먼저 찾고, 없으면 DB에서 읽어 캐시에 넣는다. */
	fun resolve(code: String): String {
		val originalUrl = cache.get(code)
			?: get(code).originalUrl.also { cache.put(code, it) }
		repository.incrementClickCount(code)
		redirectCounter.increment()
		return originalUrl
	}

	fun get(code: String): ShortUrl =
		repository.findByCode(code) ?: throw ShortUrlNotFoundException(code)

	private fun requireValidUrl(url: String) {
		// DTO 검증을 거치지 않고 create()를 직접 부를 수도 있어 여기서도 막는다. 파싱보다 먼저 봐서 긴 입력에 파서를 돌리지 않는다.
		// 막지 않으면 DB의 VARCHAR(2048) 위반이 DataIntegrityViolationException으로 올라와 코드 충돌 5번으로 오해받는다.
		if (url.length > MAX_URL_LENGTH) throw InvalidUrlException()
		val uri = try {
			URI(url)
		} catch (e: URISyntaxException) {
			throw InvalidUrlException()
		}
		if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES || uri.host.isNullOrBlank()) {
			throw InvalidUrlException()
		}
		// 사용자 정보(user@, user:pass@)가 든 URL은 거절한다.
		// https://good.com@evil.com은 good.com 링크처럼 보이지만 실제 목적지는 evil.com이라 피싱에 쓰이고,
		// https://user:pass@host는 자격 증명을 DB와 응답에 그대로 저장하게 된다.
		if (uri.rawUserInfo != null) throw InvalidUrlException()
	}

	companion object {
		const val MAX_ATTEMPTS = 5
		const val MAX_URL_LENGTH = 2048
		private val ALLOWED_SCHEMES = setOf("http", "https")
	}
}
