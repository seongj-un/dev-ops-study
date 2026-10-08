package com.devopsstudy.shortener.shorturl

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
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

	// 창의 상태를 한 덩어리로 바꿔 끼운다. 끝나는 시각과 열린 시각을 따로 두 변수에 쓰면, 그 사이에 끼어든 실패를 성공이 못 보고 새로 열린 창을 닫을 수 있다
	private class State(
		// 이 시각(origin부터 잰 나노초)까지 건너뛴다
		val skipUntil: Long,
		// 마지막 실패가 창을 연 시각이다. 시험 호출이 창을 미뤄도 바뀌지 않는다
		val openedAt: Long,
	)

	private val state = AtomicReference<State?>(null)

	private fun now() = origin.elapsedNow().inWholeNanoseconds

	/**
	 * 시각을 잰다. 이 값을 onSuccess에 넘긴다. 반드시 shouldSkip()보다 먼저 불러야 한다.
	 * shouldSkip() 뒤에 재면, 그 사이에 다른 호출이 실패해 창을 열었을 때 이 호출의 시작 시각이 열린 시각보다 늦어져서 창을 일찍 닫을 수 있다.
	 */
	fun mark(): Long = now()

	/** true면 부르지 말고 건너뛴다. 창이 막 끝났으면 물어본 쪽 하나만 false(시험 호출)를 받는다. */
	fun shouldSkip(): Boolean {
		val current = state.get() ?: return false
		val now = now()
		if (now < current.skipUntil) return true
		// 창을 먼저 한 번 더 미뤄 두고 시험한다. compareAndSet에 진 호출은 미뤄진 창을 보고 건너뛴다. 열린 시각(openedAt)은 그대로 둔다
		return !state.compareAndSet(current, State(now + durationNanos, current.openedAt))
	}

	/**
	 * 성공하면 창을 닫는다. 단, 창이 열린 뒤에 시작한 호출(startedAt >= 열린 시각)만 닫을 수 있다.
	 * 실패보다 먼저 시작해 늦게 성공한 호출은 Redis가 멀쩡하던 때의 결과라서, 방금 연 창을 닫을 근거가 못 된다.
	 * 이를 허용하면 Redis가 일부만 망가졌을 때 창이 열렸다 닫히기를 되풀이해, 타임아웃을 창(duration)마다 한 번보다 자주 치른다.
	 * @param startedAt 그 호출이 시작하기 직전에 mark()로 잰 값
	 */
	fun onSuccess(startedAt: Long) {
		// 평소(닫힘)에는 읽기만 하고 쓰지 않는다
		while (true) {
			val current = state.get() ?: return
			if (startedAt < current.openedAt) return
			// 그사이 실패나 시험 호출이 상태를 바꿨으면 다시 읽어서 판단한다
			if (state.compareAndSet(current, null)) return
		}
	}

	fun onFailure() {
		val now = now()
		state.set(State(now + durationNanos, now))
	}
}
