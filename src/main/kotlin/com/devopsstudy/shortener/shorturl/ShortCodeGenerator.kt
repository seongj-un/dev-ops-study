package com.devopsstudy.shortener.shorturl

/** 단축 코드 생성기. 테스트에서 정해진 코드를 내주는 람다로 바꿔 끼울 수 있게 인터페이스로 둔다. */
fun interface ShortCodeGenerator {
	fun generate(): String
}
