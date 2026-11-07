package org.example.project.admin.features.invoices

import org.example.project.admin.features.invoices.presentation.invoiceListDate
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Eingangsrechnungen list's Datum and Fällig columns printed the ISO date (`2026-01-15`)
 * beside every other localized date in the console. They now use the shared short date.
 */
class InvoiceListDateTest {
    @Test
    fun issueDate_isAGermanShortDate() {
        assertEquals("15.01.2026", invoiceListDate("2026-01-15T00:00", Language.GERMAN))
    }

    @Test
    fun dateOnlyWire_isAlsoFormatted() {
        assertEquals("04.04.2016", invoiceListDate("2016-04-04", Language.GERMAN))
    }

    @Test
    fun chinese_followsTheLanguageSetting() {
        assertEquals("2026年01月15日", invoiceListDate("2026-01-15T00:00", Language.CHINESE))
    }

    @Test
    fun noDate_isAnEmDash() {
        assertEquals("—", invoiceListDate(null, Language.GERMAN))
        assertEquals("—", invoiceListDate(" ", Language.GERMAN))
    }
}
