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
		cooldown.onSuccess(cooldown.mark())
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
		val startedAt = cooldown.mark()

		cooldown.onSuccess(startedAt)

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

	@Test
	fun `실패보다 먼저 시작해 늦게 성공한 호출은 방금 열린 창을 닫지 못한다`() {
		val startedAt = cooldown.mark()
		time += 100.milliseconds
		cooldown.onFailure()
		time += 100.milliseconds

		cooldown.onSuccess(startedAt)

		assertTrue(cooldown.shouldSkip(), "창은 그대로 열려 있다")
		time += 9.seconds + 899.milliseconds
		assertTrue(cooldown.shouldSkip(), "실패한 때부터 10초가 되기 1ms 전")
		time += 1.milliseconds
		assertFalse(cooldown.shouldSkip(), "10초가 되면 시험 호출")
	}

	@Test
	fun `창이 열린 뒤에 시작한 호출이 성공하면 창이 닫힌다`() {
		cooldown.onFailure()
		time += 1.seconds
		val startedAt = cooldown.mark()

		cooldown.onSuccess(startedAt)

		assertFalse(cooldown.shouldSkip())
	}

	@Test
	fun `실패와 같은 시각에 시작한 호출의 성공은 창을 닫는다`() {
		cooldown.onFailure()

		cooldown.onSuccess(cooldown.mark())

		assertFalse(cooldown.shouldSkip())
	}

	@Test
	fun `시험 호출이 창을 미뤄도 열린 시각은 그대로라서 그 전에 시작한 호출의 늦은 성공은 여전히 닫지 못한다`() {
		val early = cooldown.mark()
		time += 1.seconds
		cooldown.onFailure()
		time += 10.seconds
		assertFalse(cooldown.shouldSkip(), "시험 호출")

		cooldown.onSuccess(early)

		assertTrue(cooldown.shouldSkip(), "시험 호출 결과가 나오기 전이라 창은 미뤄진 채로 남는다")
	}

	@Test
	fun `실패가 다시 창을 열면 그 전에 시작한 시험 호출의 성공은 새 창을 닫지 못한다`() {
		cooldown.onFailure()
		time += 10.seconds
		cooldown.shouldSkip()
		val probeStartedAt = cooldown.mark()
		time += 100.milliseconds
		// 시험 호출이 도는 동안 다른 호출이 실패했다
		cooldown.onFailure()

		cooldown.onSuccess(probeStartedAt)

		assertTrue(cooldown.shouldSkip())
	}
}
