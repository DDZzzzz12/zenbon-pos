package org.example.project.features.update.data.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeferredProcessExitTest {
    @Test
    fun `exits from a thread of its own, not the caller's, and only after the caller has returned`() {
        val callerThread = Thread.currentThread()
        val exitThread = AtomicReference<Thread>()
        val exitCode = AtomicReference<Int>()
        val done = CountDownLatch(1)

        val returnedAt = System.nanoTime()
        exitFromNewThread(code = 42, delayMs = 150) {
            exitThread.set(Thread.currentThread())
            exitCode.set(it)
            done.countDown()
        }
        val callerWaited = System.nanoTime() - returnedAt

        assertTrue(done.await(5, TimeUnit.SECONDS), "the exit must run")
        assertEquals(42, exitCode.get())
        assertNotEquals(callerThread, exitThread.get(), "System.exit on the caller's (event-loop) thread deadlocks the shutdown")
        assertEquals("update-exit", exitThread.get().name)
        assertTrue(callerWaited < 100_000_000, "the caller must not wait for the delay")
    }
}
