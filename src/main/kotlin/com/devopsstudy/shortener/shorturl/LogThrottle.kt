package com.devopsstudy.shortener.shorturl

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * 장애 동안 같은 경고가 요청마다 찍혀 로그를 덮지 않게, window마다 한 번만 통과시킨다.
 * 걸러 낸 횟수는 버리지 않고 다음에 통과할 때 돌려줘서, 로그 한 줄에 "그동안 몇 번 더 났는지"를 함께 남길 수 있게 한다.
 * 시간은 벽시계가 아니라 단조 시계(TimeSource)로 잰다. 벽시계는 NTP 보정 등으로 뒤로 갈 수 있어서, 그러면 다음 로그가 한참 늦어진다.
 */
internal class LogThrottle(window: Duration, timeSource: TimeSource) {
	private val windowNanos = window.toNanos()
	private val origin = timeSource.markNow()
	private val nextAllowedAt = AtomicLong(Long.MIN_VALUE)
	private val suppressed = AtomicLong()

	/** 이번 경고를 찍어도 되면 그 전까지 걸러 낸 횟수를, 걸러야 하면 null을 돌려준다. */
	fun acquire(): Long? {
		val now = origin.elapsedNow().inWholeNanoseconds
		val next = nextAllowedAt.get()
		if (now >= next) {
			// 여러 스레드가 동시에 와도 compareAndSet에 이긴 하나만 찍는다. 이기기 전에 센 수만 이 줄에 싣고,
			// 그 뒤에 걸러진 것(같은 순간에 진 스레드 포함)은 다음 줄에 싣는다
			val carried = suppressed.get()
			if (nextAllowedAt.compareAndSet(next, now + windowNanos)) {
				suppressed.addAndGet(-carried)
				return carried
			}
		}
		suppressed.incrementAndGet()
		return null
	}
}
