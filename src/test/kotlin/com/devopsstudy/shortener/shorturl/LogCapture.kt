package com.devopsstudy.shortener.shorturl

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** 한 클래스의 로거에 붙어 그 클래스가 남긴 로그를 모은다. 테스트가 끝나면 close()로 떼어 낸다. */
class LogCapture(type: Class<*>) : AutoCloseable {
	private val logger = LoggerFactory.getLogger(type) as Logger
	private val appender = ListAppender<ILoggingEvent>().apply { start() }

	init {
		logger.addAppender(appender)
	}

	fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

	override fun close() {
		logger.detachAppender(appender)
	}
}

/** 로그 한 줄의 키-값 필드 (ECS JSON에서 최상위 필드로 나가는 값) */
fun ILoggingEvent.fields(): Map<String, Any?> = keyValuePairs.orEmpty().associate { it.key to it.value }
