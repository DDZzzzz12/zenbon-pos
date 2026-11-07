package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StabilityTrackerTest {
    private val base = Quad(Pt(10f, 10f), Pt(110f, 10f), Pt(110f, 160f), Pt(10f, 160f))
    private val diagonal = 200f

    private fun shifted(dx: Float): Quad = Quad(base.tl.copy(x = base.tl.x + dx), base.tr, base.br, base.bl)

    @Test
    fun notSettledUntilTheHoldTimeHasPassed() {
        val t = StabilityTracker(toleranceFraction = 0.01f, requiredMillis = 700)
        assertFalse(t.update(base, diagonal, nowMillis = 0))
        assertFalse(t.update(base, diagonal, nowMillis = 699))
        assertTrue(t.update(base, diagonal, nowMillis = 700))
    }

    @Test
    fun movementBeyondToleranceRestartsTheClock() {
        val t = StabilityTracker(0.01f, 700)
        t.update(base, diagonal, 0)
        assertFalse(t.update(shifted(10f), diagonal, 600)) // tolerance is 2 px
        assertFalse(t.update(shifted(10f), diagonal, 1200)) // only 600 ms since the move
        assertTrue(t.update(shifted(10f), diagonal, 1300))
    }

    @Test
    fun jitterWithinToleranceDoesNotRestartTheClock() {
        val t = StabilityTracker(0.01f, 700)
        t.update(base, diagonal, 0)
        assertTrue(t.update(shifted(1f), diagonal, 800))
    }

    @Test
    fun aMissingQuadResetsAndIsNeverSettled() {
        val t = StabilityTracker(0.01f, 700)
        t.update(base, diagonal, 0)
        assertFalse(t.update(null, diagonal, 900))
        assertFalse(t.update(base, diagonal, 1000))
        assertTrue(t.update(base, diagonal, 1700))
    }

    @Test
    fun resetForgetsTheAnchor() {
        val t = StabilityTracker(0.01f, 700)
        t.update(base, diagonal, 0)
        t.reset()
        assertFalse(t.update(base, diagonal, 800))
    }
}
