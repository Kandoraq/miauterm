package salu.Kandoraq.miauterm.pty

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class ProcessReapCoordinator {
    private val started = AtomicBoolean(false)
    private val completed = CountDownLatch(1)

    @Volatile
    private var exitCode = -1

    fun reap(waitForProcess: () -> Int): Int {
        if (started.compareAndSet(false, true)) {
            try {
                exitCode = waitForProcess()
            } catch (_: Exception) {
                exitCode = -1
            } finally {
                completed.countDown()
            }
        } else {
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        return exitCode
    }
}
