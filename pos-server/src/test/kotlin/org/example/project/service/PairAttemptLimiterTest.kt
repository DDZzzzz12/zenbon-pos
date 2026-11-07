package org.example.project.service

import org.example.project.features.terminal.data.service.PairAttemptLimiter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairAttemptLimiterTest {
    private val windowMs = 5 * 60 * 1000L

    @BeforeTest fun setUp() = PairAttemptLimiter.resetAllForTests()

    @AfterTest fun tearDown() = PairAttemptLimiter.resetAllForTests()

    @Test
    fun `5 failures then the 6th checkAllowed returns false`() {
        val key = "10.0.0.1"
        val now = 1_000_000L
        repeat(5) {
            assertTrue(PairAttemptLimiter.checkAllowed(key, now))
            PairAttemptLimiter.recordFailure(key, now)
        }
        assertFalse(PairAttemptLimiter.checkAllowed(key, now))
    }

    @Test
    fun `a new key is allowed regardless of another key's failures`() {
        val now = 1_000_000L
        repeat(5) { PairAttemptLimiter.recordFailure("10.0.0.2", now) }
        assertFalse(PairAttemptLimiter.checkAllowed("10.0.0.2", now))
        assertTrue(PairAttemptLimiter.checkAllowed("10.0.0.3", now))
    }

    @Test
    fun `success clears the counter`() {
        val key = "10.0.0.4"
        val now = 1_000_000L
        repeat(4) { PairAttemptLimiter.recordFailure(key, now) }
        PairAttemptLimiter.recordSuccess(key)
        // Counter was reset — a fresh run of MAX failures is needed to block again.
        repeat(5) {
            assertTrue(PairAttemptLimiter.checkAllowed(key, now))
            PairAttemptLimiter.recordFailure(key, now)
        }
        assertFalse(PairAttemptLimiter.checkAllowed(key, now))
    }

    @Test
    fun `window resets after WINDOW_MS elapses`() {
        val key = "10.0.0.5"
        val now = 1_000_000L
        repeat(5) { PairAttemptLimiter.recordFailure(key, now) }
        assertFalse(PairAttemptLimiter.checkAllowed(key, now))

        val afterWindow = now + windowMs + 1
        assertTrue(PairAttemptLimiter.checkAllowed(key, afterWindow))
    }

    @Test
    fun `checkAllowed alone does not track a key`() {
        val now = 1_000_000L
        repeat(100) { i -> assertTrue(PairAttemptLimiter.checkAllowed("10.1.0.$i", now)) }
        assertEquals(0, PairAttemptLimiter.trackedKeyCount())
    }

    @Test
    fun `an elapsed window is dropped when its own key checks again`() {
        val key = "10.0.0.6"
        val now = 1_000_000L
        PairAttemptLimiter.recordFailure(key, now)
        assertEquals(1, PairAttemptLimiter.trackedKeyCount())

        assertTrue(PairAttemptLimiter.checkAllowed(key, now + windowMs + 1))
        assertEquals(0, PairAttemptLimiter.trackedKeyCount())
    }

    @Test
    fun `elapsed windows of keys that never return are evicted`() {
        val now = 1_000_000L
        repeat(1_000) { i -> PairAttemptLimiter.recordFailure("10.2.${i / 250}.${i % 250}", now) }
        assertEquals(1_000, PairAttemptLimiter.trackedKeyCount())

        // A different key's request after the window elapsed purges all the stale entries.
        assertTrue(PairAttemptLimiter.checkAllowed("10.3.0.1", now + windowMs + 1))
        assertEquals(0, PairAttemptLimiter.trackedKeyCount())
    }

    @Test
    fun `eviction keeps live windows and their limit`() {
        val t0 = 1_000_000L
        val stale = "10.0.0.7"
        val live = "10.0.0.8"
        repeat(5) { PairAttemptLimiter.recordFailure(stale, t0) }
        val liveStart = t0 + windowMs / 2
        repeat(5) { PairAttemptLimiter.recordFailure(live, liveStart) }

        // The stale window has elapsed, the live one has not: only the stale one goes.
        val later = t0 + windowMs + 1
        PairAttemptLimiter.recordFailure("10.0.0.9", later)
        assertEquals(2, PairAttemptLimiter.trackedKeyCount())
        assertFalse(PairAttemptLimiter.checkAllowed(live, later))
        assertTrue(PairAttemptLimiter.checkAllowed(stale, later))

        // The live key is still blocked up to the last millisecond of its own window.
        assertFalse(PairAttemptLimiter.checkAllowed(live, liveStart + windowMs))
        assertTrue(PairAttemptLimiter.checkAllowed(live, liveStart + windowMs + 1))
    }

    @Test
    fun `a window at exactly WINDOW_MS is not evicted`() {
        val t0 = 1_000_000L
        val key = "10.0.0.10"
        repeat(5) { PairAttemptLimiter.recordFailure(key, t0) }

        PairAttemptLimiter.checkAllowed("10.0.0.11", t0 + windowMs)
        assertEquals(1, PairAttemptLimiter.trackedKeyCount())
        assertFalse(PairAttemptLimiter.checkAllowed(key, t0 + windowMs))
    }
}
