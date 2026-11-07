package org.example.project.pdf.generator

import kotlin.test.Test
import kotlin.test.assertTrue

class GenerateEigenbelegTest {
    @Test
    fun `correction eigenbeleg renders non-empty pdf`() {
        val bytes =
            generateEigenbelegPdfBytes(
                belegNr = "2026-06-0042",
                date = "24.06.2026",
                entryType = "Korrektur",
                amount = "-100,00 EUR",
                description = "Storno",
                shopName = "Test Shop",
                shopAddress = "Teststr. 1",
                correctsBelegNr = "2026-06-0040",
                correctsDate = "12.06.2026",
                reason = "Falscher Betrag",
            )
        assertTrue(bytes.size > 500, "expected a real PDF")
        assertTrue(bytes.copyOfRange(0, 4).decodeToString() == "%PDF")
    }

    @Test
    fun `plain eigenbeleg still renders`() {
        val bytes =
            generateEigenbelegPdfBytes(
                belegNr = "2026-06-0042",
                date = "24.06.2026",
                entryType = "Privatentnahme",
                amount = "100,00 EUR",
                description = "Test",
                shopName = "Test Shop",
                shopAddress = null,
            )
        assertTrue(bytes.size > 500)
    }
}
