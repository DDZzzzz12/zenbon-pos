package org.example.project.core.device

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * A clear of the device token that lands while a load is deciding whether to cache what it read is never undone by
 * that stale value.
 *
 * JVM-only because the window lies between two threads: the load's check-and-cache and the writer's assignment. The
 * clock the provider marks at the end of every load is the hook — it releases the clearing thread just as the load
 * is about to decide, and the test repeats that often enough for the two to interleave.
 */
class DeviceTokenProviderRaceTest {
    @AfterTest
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    @Test
    fun a_clear_racing_the_end_of_a_load_is_never_overwritten_by_the_stale_value() {
        repeat(ITERATIONS) { iteration ->
            DeviceTokenProvider.reset()
            val persisted = AtomicReference<String?>("tok-stale")
            val releaseClear = CountDownLatch(1)
            val clearing = AtomicBoolean(false)
            DeviceTokenProvider.loader = { persisted.get() }
            DeviceTokenProvider.timeSource =
                object : TimeSource.WithComparableMarks {
                    override fun markNow(): ComparableTimeMark {
                        // The load has read the old value and is about to decide whether to cache it: let the clear go.
                        releaseClear.countDown()
                        while (!clearing.get()) Thread.onSpinWait()
                        return TimeSource.Monotonic.markNow()
                    }
                }
            val clearer =
                thread {
                    releaseClear.await(5, TimeUnit.SECONDS)
                    // What LocalDatabaseManager.clearDeviceToken does: the persisted value first, then the holder.
                    persisted.set(null)
                    clearing.set(true)
                    DeviceTokenProvider.token = null
                }

            runBlocking { DeviceTokenProvider.currentToken() }
            clearer.join()

            assertNull(DeviceTokenProvider.token, "iteration $iteration: a clear landing as the load cached its value was undone")
        }
    }

    private companion object {
        const val ITERATIONS = 20_000
    }
}
