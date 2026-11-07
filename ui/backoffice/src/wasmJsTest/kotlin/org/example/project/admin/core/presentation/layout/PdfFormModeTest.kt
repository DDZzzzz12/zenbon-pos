package org.example.project.admin.core.presentation.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfFormModeTest {
    @Test
    fun `phone width switches`() = assertEquals(PdfFormMode.Switched, pdfFormModeFor(390.dp))

    @Test
    fun `tablet width still switches`() = assertEquals(PdfFormMode.Switched, pdfFormModeFor(768.dp))

    @Test
    fun `two columns fit from 840`() = assertEquals(PdfFormMode.SideBySide, pdfFormModeFor(840.dp))

    @Test
    fun `wide stays side by side`() = assertEquals(PdfFormMode.SideBySide, pdfFormModeFor(1400.dp))
}
