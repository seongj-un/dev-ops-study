package com.devopsstudy.shortener

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ShortenerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
