package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [formatLocalDateTime] built its English form from a `MMM` unicode pattern, which the
 * kotlinx-datetime format builder rejects: it threw for every English call, and the TSE
 * late-signing section caught that and showed the raw ISO string instead.
 */
class LocalDateTimeFormatTest {
    @Test
    fun english_usesADayMonthAbbreviationYearForm() {
        assertEquals("27 Sep 2026, 10:05", "2026-09-27T10:05:33.123456".formatLocalDateTime(Language.ENGLISH))
    }

    @Test
    fun english_padsTheDayAndCoversEveryMonth() {
        assertEquals("03 Jan 2026, 00:00", "2026-01-03T00:00".formatLocalDateTime(Language.ENGLISH))
        assertEquals("31 Dec 2026, 23:59", "2026-12-31T23:59:59".formatLocalDateTime(Language.ENGLISH))
    }

    @Test
    fun german_isTheDottedDateAndMinute() {
        assertEquals("27.09.2026, 10:05", "2026-09-27T10:05:33".formatLocalDateTime(Language.GERMAN))
    }

    @Test
    fun chinese_isTheYearMonthDayForm() {
        assertEquals("2026年09月27日 10:05", "2026-09-27T10:05".formatLocalDateTime(Language.CHINESE))
    }

    @Test
    fun unparseableInput_isReturnedUnchangedInEveryLanguage() {
        Language.entries.forEach { language ->
            assertEquals("gestern", "gestern".formatLocalDateTime(language))
        }
    }
}
