package org.example.project.ui.components.pdf

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfColumnWidthTest {
    @Test
    fun aZoomedOutPageGetsAColumnAsWideAsTheViewportSoItCanBeCentred() {
        assertEquals(400.dp, pdfColumnWidth(viewportWidth = 400.dp, pageWidth = 192.dp, gutter = 8.dp))
    }

    @Test
    fun aPageAtFitWidthFillsTheViewportExactly() {
        assertEquals(400.dp, pdfColumnWidth(viewportWidth = 400.dp, pageWidth = 384.dp, gutter = 8.dp))
    }

    @Test
    fun aZoomedInPageWidensTheColumnBeyondTheViewportForPanning() {
        assertEquals(784.dp, pdfColumnWidth(viewportWidth = 400.dp, pageWidth = 768.dp, gutter = 8.dp))
    }
}
