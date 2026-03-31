package com.example.batteryexpcollector

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CpuStressController {

    @Volatile
    private var workers: List<Thread> = emptyList()

    private val running = AtomicBoolean(false)

    fun start(threadCount: Int, dutyPercent: Int) {
        stop()

        val resolvedThreadCount = resolveThreadCount(threadCount)
        val resolvedDutyPercent = dutyPercent.coerceIn(10, 100)
        running.set(true)

        workers = List(resolvedThreadCount) { index ->
            Thread(
                {
                    runWorkerLoop(resolvedDutyPercent)
                },
                "cpu-stress-$index"
            ).apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    fun stop() {
        running.set(false)
        workers.forEach { worker ->
            worker.interrupt()
            runCatching { worker.join(500L) }
        }
        workers = emptyList()
    }

    fun isRunning(): Boolean = running.get() && workers.isNotEmpty()

    private fun resolveThreadCount(requestedThreadCount: Int): Int {
        if (requestedThreadCount > 0) {
            return requestedThreadCount.coerceAtMost(MAX_THREADS)
        }

        val available = Runtime.getRuntime().availableProcessors()
        return min(MAX_THREADS, max(1, available - 1))
    }

    private fun runWorkerLoop(dutyPercent: Int) {
        val cycleMs = 100L
        val busyMs = (cycleMs * dutyPercent) / 100L
        val idleMs = cycleMs - busyMs
        var accumulator = 0.123456789

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val busyUntil = SystemClock.elapsedRealtime() + busyMs
            while (running.get() &&
                !Thread.currentThread().isInterrupted &&
                SystemClock.elapsedRealtime() < busyUntil
            ) {
                accumulator = performBusyWork(accumulator)
            }

            if (idleMs > 0 && running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(idleMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun performBusyWork(seed: Double): Double {
        var value = seed
        repeat(5_000) {
            value += Math.sqrt(value + it)
            value %= 10_000.0
        }
        return value
    }

    companion object {
        private const val MAX_THREADS = 6
    }
}
