package org.example.project.admin.core.util

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.formatIsoDateRange
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.formatPercentForDisplay
import org.example.project.ui.components.formatSuffixedPercentForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared display formatters adminApp routes its percent, date-range and date-time cells
 * through, pinned on the wasm leg the back office actually runs (QA admin17 NOTE-12/13).
 */
class DisplayFormatTest {
    @Test
    fun paymentShareIsLocalized() {
        // Berichte → Zahlungsarten „Anteil“ showed the server's raw "46.39%".
        assertEquals("46,39 %", formatPercentForDisplay("46.39", Language.GERMAN))
        assertEquals("46.39%", formatPercentForDisplay("46.39", Language.ENGLISH))
        assertEquals("46.39%", formatPercentForDisplay("46.39", Language.CHINESE))
    }

    @Test
    fun signedDeltasKeepTheirSign() {
        assertEquals("-3,5 %", formatPercentForDisplay("-3.50", Language.GERMAN))
        assertEquals("12.25%", formatPercentForDisplay("12.25", Language.ENGLISH))
    }

    @Test
    fun percentDefaultsToGermanAndPassesGarbageThrough() {
        assertEquals("19 %", formatPercentForDisplay("19.00"))
        assertEquals("n/a", formatPercentForDisplay("n/a", Language.ENGLISH))
    }

    @Test
    fun dsfinvkRangeDropsTheEnglishTo() {
        assertEquals("01.10.2026 – 02.10.2026", "2026-10-01 to 2026-10-02".formatIsoDateRange(Language.GERMAN))
        assertEquals("01.10.2026 – 02.10.2026", "2026-10-01..2026-10-02".formatIsoDateRange(Language.ENGLISH))
        assertEquals("2026年10月01日 – 2026年10月02日", "2026-10-01 to 2026-10-02".formatIsoDateRange(Language.CHINESE))
    }

    @Test
    fun anUnreadableRangeIsShownAsSent() {
        assertEquals("last week", "last week".formatIsoDateRange(Language.GERMAN))
        assertEquals("2026-10-01 to soon", "2026-10-01 to soon".formatIsoDateRange(Language.GERMAN))
    }

    @Test
    fun billAndKassenbuchTimesUseTheShortDateTime() {
        // „2 Okt 2026, 13:32“ in the bill panel and the Kassenbuch TSE-Signierzeit.
        assertEquals("02.10.2026 13:32", "2026-10-02T13:32:07.123456".formatShortDateTime(Language.GERMAN))
        assertEquals("02.10.2026 14:16", "2026-10-02T14:16".formatShortDateTime(Language.ENGLISH))
    }

    @Test
    fun serverSuffixedVatPercentagesAreReformatted() {
        // VatResponseDto.vatPercentage arrives as "19.00%".
        assertEquals("19 %", formatSuffixedPercentForDisplay("19.00%", Language.GERMAN))
        assertEquals("7%", formatSuffixedPercentForDisplay("7.00%", Language.ENGLISH))
        assertEquals("n/a", formatSuffixedPercentForDisplay("n/a", Language.GERMAN))
    }
}
