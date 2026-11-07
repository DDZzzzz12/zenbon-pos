package org.example.project.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HorizontalWheelScrollTest {
    @Test
    fun verticalWheelMapsOneToOneToHorizontal() {
        assertEquals(100f, wheelScrollAmount(0f, 100f, canScrollForward = true, canScrollBackward = false))
        assertEquals(-100f, wheelScrollAmount(0f, -100f, canScrollForward = false, canScrollBackward = true))
    }

    @Test
    fun atTheEndTheEventFallsThroughToThePage() {
        assertNull(wheelScrollAmount(0f, 100f, canScrollForward = false, canScrollBackward = true))
        assertNull(wheelScrollAmount(0f, -100f, canScrollForward = true, canScrollBackward = false))
    }

    @Test
    fun horizontalDeltasAreLeftToTheNativeHandler() {
        assertNull(wheelScrollAmount(40f, 0f, canScrollForward = true, canScrollBackward = true))
        assertNull(wheelScrollAmount(40f, 100f, canScrollForward = true, canScrollBackward = true))
    }

    @Test
    fun zeroDeltaDoesNothing() {
        assertNull(wheelScrollAmount(0f, 0f, canScrollForward = true, canScrollBackward = true))
    }

    @Test
    fun wheelTickIsOneHundredCssPixelsScaledByDensity() {
        assertEquals(100f, wheelTickPx(1f))
        assertEquals(200f, wheelTickPx(2f))
    }

    @Test
    fun arrowStepIsTwoWheelTicksAtAnyDensity() {
        assertEquals(200f, arrowStepPx(1f))
        assertEquals(400f, arrowStepPx(2f))
    }
}
