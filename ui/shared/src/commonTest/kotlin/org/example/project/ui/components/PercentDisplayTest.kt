package org.example.project.ui.components

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Z-Bon's VAT table printed the server's "19.00" raw as "19.00%" (emulator pass 4, NOTE-6). */
class PercentDisplayTest {
    @Test
    fun wholeRatesDropTheirDecimals() {
        assertEquals("19 %", formatPercentForDisplay("19.00"))
        assertEquals("7 %", formatPercentForDisplay("7"))
        assertEquals("0 %", formatPercentForDisplay("0.0000"))
    }

    @Test
    fun fractionalRatesUseADecimalComma() {
        assertEquals("5,5 %", formatPercentForDisplay("5.50"))
        assertEquals("10,7 %", formatPercentForDisplay("10.70"))
        assertEquals("12,35 %", formatPercentForDisplay("12.345"))
    }

    @Test
    fun unparseableInputIsShownAsIs() {
        assertEquals("n/a", formatPercentForDisplay("n/a"))
    }

    @Test
    fun englishAndChineseUseADecimalPointAndNoSpace() {
        assertEquals("46.39%", formatPercentForDisplay("46.39", Language.ENGLISH))
        assertEquals("19%", formatPercentForDisplay("19.00", Language.CHINESE))
        assertEquals("46,39 %", formatPercentForDisplay("46.39", Language.GERMAN))
    }
}
