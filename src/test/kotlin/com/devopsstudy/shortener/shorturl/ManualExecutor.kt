package com.devopsstudy.shortener.shorturl

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 맡긴 작업을 쌓아 두기만 하고, 테스트가 runPending()을 부를 때 그 스레드에서 차례로 돌리는 실행기.
 * 작업이 언제 도는지를 테스트가 정하므로 결과가 늘 같다. 대기열이 capacity만큼 차거나 shutdown된 뒤에는
 * ThreadPoolExecutor(AbortPolicy)처럼 RejectedExecutionException을 던진다.
 */
class ManualExecutor(private val capacity: Int = Int.MAX_VALUE) : AbstractExecutorService() {
	private val pending = ArrayDeque<Runnable>()
	private var shutdown = false

	val pendingCount: Int get() = pending.size

	override fun execute(command: Runnable) {
		if (shutdown || pending.size >= capacity) throw RejectedExecutionException("manual executor rejected")
		pending.addLast(command)
	}

	fun runPending() {
		while (pending.isNotEmpty()) pending.removeFirst().run()
	}

	override fun shutdown() {
		shutdown = true
	}

	override fun shutdownNow(): List<Runnable> {
		shutdown = true
		return pending.toList().also { pending.clear() }
	}

	override fun isShutdown() = shutdown

	override fun isTerminated() = shutdown && pending.isEmpty()

	// 아무도 대신 돌려 주지 않으므로 기다려도 바뀌지 않는다. 지금 끝났는지만 돌려준다
	override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}
