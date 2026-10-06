package com.devopsstudy.shortener.shorturl

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CooldownTest {
	private val time = TestTimeSource()
	private val cooldown = Cooldown(Duration.ofSeconds(10), time)

	@Test
	fun `실패한 적이 없으면 건너뛰지 않는다`() {
		assertFalse(cooldown.shouldSkip())
		cooldown.onSuccess()
		assertFalse(cooldown.shouldSkip())
	}

	@Test
	fun `실패하면 그때부터 10초 동안 건너뛴다`() {
		time += 5.seconds
		cooldown.onFailure()

		assertTrue(cooldown.shouldSkip())
		time += 9.seconds + 999.milliseconds
		assertTrue(cooldown.shouldSkip(), "10초가 되기 1ms 전")
	}

	@Test
	fun `창이 끝나면 하나만 시험하고 나머지는 그 결과가 나올 때까지 건너뛴다`() {
		cooldown.onFailure()
		time += 10.seconds

		assertFalse(cooldown.shouldSkip(), "처음 물어본 호출은 시험 삼아 부른다")
		assertTrue(cooldown.shouldSkip(), "시험 호출이 끝나기 전의 다른 호출은 건너뛴다")
		assertTrue(cooldown.shouldSkip())
	}

	@Test
	fun `시험 호출이 성공하면 닫혀서 다시 늘 부른다`() {
		cooldown.onFailure()
		time += 10.seconds
		cooldown.shouldSkip()

		cooldown.onSuccess()

		assertFalse(cooldown.shouldSkip())
		assertFalse(cooldown.shouldSkip())
	}

	@Test
	fun `시험 호출이 실패하면 실패한 때부터 다시 10초 동안 건너뛴다`() {
		cooldown.onFailure()
		time += 10.seconds
		cooldown.shouldSkip()
		// 시험 호출이 타임아웃까지 걸린 뒤 실패했다
		time += 200.milliseconds
		cooldown.onFailure()

		time += 9.seconds + 999.milliseconds
		assertTrue(cooldown.shouldSkip())
		time += 1.milliseconds
		assertFalse(cooldown.shouldSkip(), "다음 시험 호출")
	}

	@Test
	fun `시험 호출이 결과를 남기지 못해도 다음 창이 끝나면 다시 시험한다`() {
		// 시험 호출이 Redis 오류가 아닌 다른 예외로 끝나면 onSuccess도 onFailure도 불리지 않는다. 그래도 영원히 건너뛰지는 않는다
		cooldown.onFailure()
		time += 10.seconds
		cooldown.shouldSkip()

		time += 10.seconds

		assertFalse(cooldown.shouldSkip())
	}
}
