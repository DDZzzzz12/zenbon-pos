package org.example.project.features.order

import org.example.project.features.order.domain.service.nextInRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextInRotationTest {
    private val pool = listOf("a", "b", "c")

    @Test
    fun emptyPoolReturnsNull() {
        assertNull(nextInRotation(emptyList<String>(), "a"))
    }

    @Test
    fun nullCursorReturnsFirst() {
        assertEquals("a", nextInRotation(pool, null))
    }

    @Test
    fun cursorInPoolReturnsFollowing() {
        assertEquals("c", nextInRotation(pool, "b"))
    }

    @Test
    fun wrapsAtEnd() {
        assertEquals("a", nextInRotation(pool, "c"))
    }

    @Test
    fun cursorNotInPoolRestartsAtFirst() {
        assertEquals("a", nextInRotation(pool, "x"))
    }

    @Test
    fun singleEmployeeReturnsThemself() {
        assertEquals("a", nextInRotation(listOf("a"), "a"))
    }
}
