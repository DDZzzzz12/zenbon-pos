package org.example.project.admin.core.util.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the hidden print frame and its blob URL are freed; driven by a fake clock. */
class PrintFrameReleaseTest {
    private class FakeClock {
        private var now = 0
        private var nextId = 1
        private val timers = mutableMapOf<Int, Pair<Int, () -> Unit>>()

        fun set(
            delayMs: Int,
            action: () -> Unit,
        ): Int = nextId++.also { timers[it] = (now + delayMs) to action }

        fun clear(id: Int) {
            timers.remove(id)
        }

        fun advance(ms: Int) {
            now += ms
            val due = timers.filterValues { it.first <= now }.keys.sorted()
            due.forEach { id -> timers.remove(id)?.second?.invoke() }
        }

        val pending: Int get() = timers.size
    }

    private val clock = FakeClock()
    private val subject = PrintFrameRelease(clock::set, clock::clear, fallbackMs = 600_000, graceMs = 5_000)

    @Test
    fun afterPrintReleasesOnceTheGracePeriodHasPassed() {
        var released = 0
        val afterPrint = subject.hold { released++ }
        afterPrint()
        clock.advance(4_999)
        assertEquals(0, released, "the browser may still be spooling from the frame")
        clock.advance(1)
        assertEquals(1, released)
        assertFalse(subject.isHolding)
        assertEquals(0, clock.pending, "the fallback timer is cancelled with the release")
    }

    @Test
    fun withoutAfterPrintTheFallbackTimerReleases() {
        var released = 0
        subject.hold { released++ }
        clock.advance(599_999)
        assertEquals(0, released)
        clock.advance(1)
        assertEquals(1, released)
    }

    @Test
    fun theNextPrintReleasesThePreviousAtOnce() {
        val released = mutableListOf<String>()
        subject.hold { released += "first" }
        subject.hold { released += "second" }
        assertEquals(listOf("first"), released)
        assertTrue(subject.isHolding)
        assertEquals(1, clock.pending, "only the current print's timer is left")
    }

    @Test
    fun aLateAfterPrintOfAnEarlierPrintDoesNotTouchTheCurrentOne() {
        val released = mutableListOf<String>()
        val firstAfterPrint = subject.hold { released += "first" }
        subject.hold { released += "second" }
        firstAfterPrint()
        clock.advance(5_000)
        assertEquals(listOf("first"), released, "the second print is still in its dialog")
        assertTrue(subject.isHolding)
    }

    @Test
    fun sessionEndReleasesAtOnceAndOnlyOnce() {
        var released = 0
        val afterPrint = subject.hold { released++ }
        subject.releaseNow()
        subject.releaseNow()
        afterPrint()
        clock.advance(600_000)
        assertEquals(1, released)
        assertEquals(0, clock.pending)
    }

    @Test
    fun releaseNowWithNothingHeldIsANoOp() {
        subject.releaseNow()
        assertFalse(subject.isHolding)
    }
}
