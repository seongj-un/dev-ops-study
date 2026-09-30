package com.devopsstudy.shortener.shorturl

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Base62(0-9, a-z, A-Z) 문자로 무작위 코드를 만든다.
 * 순번(ID)을 Base62로 바꾸는 방식과 달리 다음 코드를 추측할 수 없다.
 * 7자리면 62^7 ≈ 3.5조 가지라 충돌은 드물고, 충돌하면 ShortUrlService가 다시 뽑는다.
 */
@Component
class Base62ShortCodeGenerator : ShortCodeGenerator {
	private val random = SecureRandom()

	override fun generate(): String = buildString(CODE_LENGTH) {
		repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
	}

	companion object {
		const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
		const val CODE_LENGTH = 7
	}
}
