package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfPageJumpEditTest {
    /** Go: the field takes the clamped target, then loses focus — with the page it was composed for, still 1. */
    @Test
    fun goPastTheEndOfATwoPageDocumentShowsTheLastPageEvenWhenTheCurrentPageDoesNotMove() {
        val typed = PdfPageJumpEdit.showing(1).typed("12")
        val target = typed.target(currentPage = 1, pageCount = 2)
        val afterGo = PdfPageJumpEdit.showing(target).focusLost(currentPage = 1)
        assertEquals(2, target)
        assertEquals("2", afterGo.text)
    }

    @Test
    fun goWithUnreadableInputRevertsToTheCurrentPage() {
        val typed = PdfPageJumpEdit.showing(2).typed("")
        val target = typed.target(currentPage = 2, pageCount = 3)
        assertEquals(2, target)
        assertEquals("2", PdfPageJumpEdit.showing(target).focusLost(currentPage = 2).text)
    }

    @Test
    fun leavingTheFieldWithoutGoRevertsToTheCurrentPage() {
        val abandoned = PdfPageJumpEdit.showing(1).typed("5").focusLost(currentPage = 1)
        assertEquals("1", abandoned.text)
        assertFalse(abandoned.editing)
    }

    @Test
    fun focusLossWithoutTypingLeavesTheTextAlone() {
        val shown = PdfPageJumpEdit.showing(3)
        assertEquals(shown, shown.focusLost(currentPage = 1))
    }

    @Test
    fun typingKeepsDigitsOnlyAndAtMostFiveOfThem() {
        val typed = PdfPageJumpEdit.showing(1).typed("1a2-345678")
        assertEquals("12345", typed.text)
        assertTrue(typed.editing)
    }

    /** Esc: cancels the edit outright, restoring the current page whether or not anything was typed. */
    @Test
    fun cancelRestoresTheCurrentPageAndEndsTheEdit() {
        val cancelled = PdfPageJumpEdit.showing(1).typed("99").cancel(currentPage = 4)
        assertEquals("4", cancelled.text)
        assertFalse(cancelled.editing)
        assertEquals("4", PdfPageJumpEdit.showing(7).cancel(currentPage = 4).text)
    }
}
