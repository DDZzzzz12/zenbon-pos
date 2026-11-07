package org.example.project.core.utils

import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R6-3: the adminApp Beleg detail rendered its upload time as the raw server string
 * `2026-09-26T23:56:11.694604`. [formatShortDateTime] is the display form.
 */
class ShortDateTimeFormatTest {
    // A fixed offset, not a region id: the wasm/js legs carry no tz database.
    private val cest = UtcOffset(hours = 2).asTimeZone()

    @Test
    fun localDateTimeWithMicroseconds_rendersGermanDateAndMinute() {
        assertEquals("26.09.2026 23:56", "2026-09-26T23:56:11.694604".formatShortDateTime(Language.GERMAN))
    }

    @Test
    fun chinese_usesTheYearMonthDayForm() {
        assertEquals("2026年09月26日 07:05", "2026-09-26T07:05".formatShortDateTime(Language.CHINESE))
    }

    @Test
    fun dateOnlyInput_rendersAsAShortDate() {
        assertEquals("15.01.2026", "2026-01-15".formatShortDateTime(Language.ENGLISH))
    }

    @Test
    fun unparseableInput_isReturnedUnchanged() {
        assertEquals("gestern T", "gestern T".formatShortDateTime(Language.GERMAN))
    }

    // The Belege list's Datum column: a midnight LocalDateTime clipped to "2026-01-15T" on screen.
    @Test
    fun shortDate_dropsTheTimeOfALocalDateTime() {
        assertEquals("15.01.2026", "2026-01-15T00:00".formatShortDate(Language.GERMAN))
    }

    // NOTE-13 (admin18): the Datenexport scheduler cards printed "2026-10-02 17:59".
    @Test
    fun epochMillis_rendersInTheGivenZoneAsShortDateTime() {
        val millis = 1_759_420_740_000L // 2025-10-02T15:59:00Z
        assertEquals("02.10.2025 17:59", millis.formatEpochMillisShortDateTime(Language.GERMAN, cest))
    }

    // NOTE-13 (admin18): "Letztes Backup" printed the raw instant "2026-09-27T01:00:42.688084035Z".
    @Test
    fun isoInstant_rendersInTheGivenZoneAsShortDateTime() {
        assertEquals(
            "27.09.2026 03:00",
            "2026-09-27T01:00:42.688084035Z".formatInstantShortDateTime(Language.GERMAN, cest),
        )
    }

    @Test
    fun localDateTimeInput_fallsBackToTheLocalForm() {
        assertEquals("27.09.2026 01:00", "2026-09-27T01:00".formatInstantShortDateTime(Language.GERMAN, cest))
    }

    @Test
    fun unparseableInstantInput_isReturnedUnchanged() {
        assertEquals("nie", "nie".formatInstantShortDateTime(Language.GERMAN, cest))
    }

    // admin20 NOTE-D: the bill panel's TSE block printed the raw instant.
    @Test
    fun tseInstant_rendersLocalDateAndTimeWithSeconds() {
        assertEquals("03.10.2026 12:01:38", "2026-10-03T10:01:38.000Z".formatInstantShortDateTimeSeconds(Language.GERMAN, cest))
        assertEquals("03.10.2026 12:01:38", "2026-10-03T12:01:38".formatInstantShortDateTimeSeconds(Language.GERMAN, cest))
        assertEquals("kaputt", "kaputt".formatInstantShortDateTimeSeconds(Language.GERMAN, cest))
    }

    @Test
    fun yearMonth_followsTheUiLanguage() {
        assertEquals("03.2026", "2026-03".formatYearMonth(Language.GERMAN))
        assertEquals("03.2026", "2026-03".formatYearMonth(Language.ENGLISH))
        assertEquals("2026年03月", "2026-03".formatYearMonth(Language.CHINESE))
    }

    @Test
    fun yearMonth_acceptsAFullIsoDate() {
        assertEquals("03.2026", "2026-03-15".formatYearMonth(Language.GERMAN))
        assertEquals("2026年03月", "2026-03-15T10:00:00".formatYearMonth(Language.CHINESE))
    }

    @Test
    fun yearMonth_unparseableIsReturnedUnchanged() {
        assertEquals("bald", "bald".formatYearMonth(Language.GERMAN))
        assertEquals("2026-13", "2026-13".formatYearMonth(Language.GERMAN))
    }
}
