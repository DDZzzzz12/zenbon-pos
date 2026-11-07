package org.example.project.ui.components

import org.example.project.core.domain.reason.ActionReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasonSelectionStateTest {
    private val reasons = ActionReasons.cancelItems
    private val first = reasons.first()
    private val second = reasons[1]
    private val other = reasons.last()

    private fun state() = ReasonSelectionState(reasons)

    @Test
    fun startsInvalidWithNoValue() {
        val s = state()
        assertFalse(s.isValid)
        assertNull(s.value)
    }

    @Test
    fun chipSelectionGivesItsGermanStoredText() {
        val s = state()
        s.select(first)
        assertTrue(s.isValid)
        assertEquals("Falscher Artikel erfasst", s.value)
    }

    @Test
    fun reTappingTheSelectedChipClearsIt() {
        val s = state()
        s.select(first)
        s.select(first)
        assertFalse(s.isValid)
        assertNull(s.value)
    }

    @Test
    fun otherWithBlankTextIsInvalid() {
        val s = state()
        s.select(other)
        assertFalse(s.isValid)
        s.onOtherTextChange("   ")
        assertFalse(s.isValid)
        assertNull(s.value)
    }

    @Test
    fun otherTextIsTrimmedAndReturned() {
        val s = state()
        s.select(other)
        s.onOtherTextChange("  Gast war betrunken ")
        assertTrue(s.isValid)
        assertEquals("Gast war betrunken", s.value)
    }

    @Test
    fun switchingChipDropsTheOtherText() {
        val s = state()
        s.select(other)
        s.onOtherTextChange("abc")
        s.select(second)
        s.select(other)
        assertEquals("", s.otherText)
        assertFalse(s.isValid)
    }

    @Test
    fun otherTextIsTruncatedAt500() {
        val s = state()
        s.select(other)
        s.onOtherTextChange("x".repeat(600))
        assertEquals(500, s.otherText.length)
    }
}
