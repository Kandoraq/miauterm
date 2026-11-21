package salu.Kandoraq.miauterm.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessReapCoordinatorTest {
    @Test
    fun concurrentWaitersReapChildOnlyOnceAndShareExitCode() {
        val coordinator = ProcessReapCoordinator()
        val waitCalls = AtomicInteger()
        val waitStarted = CountDownLatch(1)
        val allowExit = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)

        try {
            val first = workers.submit<Int> {
                coordinator.reap {
                    waitCalls.incrementAndGet()
                    waitStarted.countDown()
                    check(allowExit.await(2, TimeUnit.SECONDS))
                    143
                }
            }
            assertTrue(waitStarted.await(2, TimeUnit.SECONDS))
            val second = workers.submit<Int> {
                coordinator.reap {
                    waitCalls.incrementAndGet()
                    0
                }
            }
            allowExit.countDown()

            assertEquals(143, first.get(2, TimeUnit.SECONDS))
            assertEquals(143, second.get(2, TimeUnit.SECONDS))
            assertEquals(1, waitCalls.get())
        } finally {
            allowExit.countDown()
            workers.shutdownNow()
        }
    }
}
