package com.devopsstudy.shortener.shorturl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Base62ShortCodeGeneratorTest {
	private val generator = Base62ShortCodeGenerator()

	@Test
	fun `코드는 7자리 Base62 문자로만 이루어진다`() {
		repeat(1_000) {
			val code = generator.generate()
			assertEquals(7, code.length)
			assertTrue(code.all { it in Base62ShortCodeGenerator.ALPHABET }, "unexpected character in $code")
		}
	}

	@Test
	fun `만 번 뽑아도 겹치지 않는다`() {
		// 62^7 ≈ 3.5조 가지라 1만 개 중 겹칠 확률은 n²/2N ≈ 1.4e-5, 약 0.0014%
		val codes = List(10_000) { generator.generate() }.toSet()
		assertEquals(10_000, codes.size)
	}

	@Test
	fun `알파벳 62자를 모두 사용한다`() {
		val used = List(10_000) { generator.generate() }.joinToString("").toSet()
		assertEquals(Base62ShortCodeGenerator.ALPHABET.toSet(), used)
	}
}
