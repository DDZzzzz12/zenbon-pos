package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfPageJumpTargetTest {
    @Test
    fun aPageInRangeIsTaken() {
        assertEquals(2, pdfPageJumpTarget(typed = "2", currentPage = 1, pageCount = 3))
    }

    @Test
    fun aPageBeyondTheEndIsClampedToTheLastPage() {
        assertEquals(2, pdfPageJumpTarget(typed = "12", currentPage = 1, pageCount = 2))
    }

    @Test
    fun pageZeroIsClampedToTheFirstPage() {
        assertEquals(1, pdfPageJumpTarget(typed = "0", currentPage = 2, pageCount = 2))
    }

    @Test
    fun emptyInputKeepsTheCurrentPage() {
        assertEquals(2, pdfPageJumpTarget(typed = "", currentPage = 2, pageCount = 3))
    }

    @Test
    fun nonNumericInputKeepsTheCurrentPage() {
        assertEquals(3, pdfPageJumpTarget(typed = "x", currentPage = 3, pageCount = 3))
    }

    @Test
    fun aNumberTooLargeForAnIntIsClampedRatherThanIgnored() {
        assertEquals(2, pdfPageJumpTarget(typed = "99999999999", currentPage = 1, pageCount = 2))
    }

    @Test
    fun aDocumentWithoutPagesStaysOnPageOne() {
        assertEquals(1, pdfPageJumpTarget(typed = "5", currentPage = 1, pageCount = 0))
    }
}
