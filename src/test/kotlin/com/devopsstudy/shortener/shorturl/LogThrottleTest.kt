package com.devopsstudy.shortener.shorturl

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LogThrottleTest {
	private val time = TestTimeSource()
	private val throttle = LogThrottle(Duration.ofSeconds(10), time)

	@Test
	fun `처음 경고는 통과하고 걸러 낸 수는 0이다`() {
		assertEquals(0L, throttle.acquire())
	}

	@Test
	fun `창 안의 경고는 거르고 창이 지나면 걸러 낸 수와 함께 통과시킨다`() {
		throttle.acquire()

		assertNull(throttle.acquire())
		time += 9.seconds
		assertNull(throttle.acquire())
		time += 999.milliseconds
		assertNull(throttle.acquire())
		time += 1.milliseconds

		assertEquals(3L, throttle.acquire(), "10초 동안 거른 세 번")
		assertNull(throttle.acquire(), "새 창이 시작됐다")
	}

	@Test
	fun `한참 조용하다가 난 경고는 바로 통과한다`() {
		throttle.acquire()
		time += 1.seconds
		throttle.acquire()

		time += 60.seconds

		assertEquals(1L, throttle.acquire())
	}
}
