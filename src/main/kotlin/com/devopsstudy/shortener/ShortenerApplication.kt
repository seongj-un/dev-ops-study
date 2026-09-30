package com.devopsstudy.shortener

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ShortenerApplication

fun main(args: Array<String>) {
	runApplication<ShortenerApplication>(*args)
}
