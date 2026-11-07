package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KpiSummaryTextTest {
    @Test
    fun nullValueRendersEmptyValueNotZero() {
        val text = kpiSummaryText(listOf(KpiSummaryItem("Ausgegeben", "0,00 €"), KpiSummaryItem("Eingelöst", null)))
        assertEquals("Ausgegeben 0,00 € · Eingelöst —", text)
        assertFalse(text.substringAfter("Eingelöst").contains("0"))
    }

    @Test
    fun emptyListGivesEmptyString() {
        assertEquals("", kpiSummaryText(emptyList()))
    }
}
