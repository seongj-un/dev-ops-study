package com.devopsstudy.shortener.shorturl

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * 의존성(Redis) 호출이 실패하면 duration 동안 부르지 말라고 알려 주는 창. 아주 단순한 서킷 브레이커다.
 * - 닫힘: 평소. 늘 부른다.
 * - 열림: 실패한 뒤 duration 동안. 부르지 않고 건너뛴다. 그동안 요청은 타임아웃을 기다리지 않는다.
 * - 창이 끝나면 처음 물어본 호출 하나만 시험 삼아 부른다. 나머지는 그 결과가 나올 때까지 계속 건너뛴다.
 *   성공하면 닫히고, 실패하면 다시 duration만큼 열린다. 창이 끝날 때마다 모든 요청이 한꺼번에 타임아웃을 치르지 않게 하려는 것이다.
 * 시간은 단조 시계(TimeSource)로 잰다. 벽시계가 뒤로 가면 창이 그만큼 길어지기 때문이다.
 */
internal class Cooldown(duration: Duration, timeSource: TimeSource) {
	private val durationNanos = duration.toNanos()
	private val origin = timeSource.markNow()

	// CLOSED면 닫힘, 아니면 이 시각(origin부터 잰 나노초)까지 건너뛴다
	private val skipUntil = AtomicLong(CLOSED)

	private fun now() = origin.elapsedNow().inWholeNanoseconds

	/** true면 부르지 말고 건너뛴다. 창이 막 끝났으면 물어본 쪽 하나만 false(시험 호출)를 받는다. */
	fun shouldSkip(): Boolean {
		val until = skipUntil.get()
		if (until == CLOSED) return false
		val now = now()
		if (now < until) return true
		// 창을 먼저 한 번 더 미뤄 두고 시험한다. compareAndSet에 진 호출은 미뤄진 창을 보고 건너뛴다
		return !skipUntil.compareAndSet(until, now + durationNanos)
	}

	fun onSuccess() {
		// 평소(닫힘)에는 읽기만 하고 쓰지 않는다
		if (skipUntil.get() != CLOSED) skipUntil.set(CLOSED)
	}

	fun onFailure() {
		skipUntil.set(now() + durationNanos)
	}

	private companion object {
		const val CLOSED = Long.MIN_VALUE
	}
}
