package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfTopPageTest {
    @Test
    fun midDocumentNamesFirstVisiblePage() {
        assertEquals(1, pdfTopPageIndex(firstVisible = 1, lastVisible = 2, canScrollForward = true, canScrollBackward = true))
    }

    @Test
    fun scrolledToEndNamesLastVisiblePage() {
        assertEquals(2, pdfTopPageIndex(firstVisible = 1, lastVisible = 2, canScrollForward = false, canScrollBackward = true))
    }

    @Test
    fun documentThatFitsStaysOnFirstPage() {
        assertEquals(0, pdfTopPageIndex(firstVisible = 0, lastVisible = 2, canScrollForward = false, canScrollBackward = false))
    }
}
