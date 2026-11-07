package org.example.project.core.utils

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The typed side of the one date field (UI-P1-08): display format in, ISO out, and back again. */
class ShortDateParseTest {
    private val march1 = LocalDate(2026, 3, 1)

    @Test
    fun parsesWhatFormatShortDatePrintsInEveryLanguage() {
        Language.entries.forEach { language ->
            val shown = march1.toString().formatShortDate(language)
            assertEquals(march1, parseShortDate(shown), "round trip in $language via '$shown'")
        }
    }

    @Test
    fun acceptsUnpaddedGermanAndChineseAndAPastedIsoDate() {
        assertEquals(march1, parseShortDate("1.3.2026"))
        assertEquals(march1, parseShortDate(" 01.03.2026 "))
        assertEquals(march1, parseShortDate("2026年3月1日"))
        assertEquals(march1, parseShortDate("2026-03-01"))
        assertEquals(march1, parseShortDate("2026/3/1"))
    }

    @Test
    fun refusesHalfTypedImpossibleAndTwoDigitYearDates() {
        assertNull(parseShortDate(""))
        assertNull(parseShortDate("01.03.20"))
        assertNull(parseShortDate("01.03."))
        assertNull(parseShortDate("31.02.2026"))
        assertNull(parseShortDate("2026-13-01"))
        assertNull(parseShortDate("morgen"))
    }

    @Test
    fun wireValueIsIsoForADateBlankForBlankAndTheTextOtherwise() {
        assertEquals("2026-03-01", dateWireValue("01.03.2026"))
        assertEquals("2026-03-01", dateWireValue("2026年03月01日"))
        assertEquals("", dateWireValue("   "))
        assertEquals("31.02.2026", dateWireValue(" 31.02.2026 "))
    }

    @Test
    fun inputTextShowsAnIsoValueInTheUiLanguageAndLeavesEverythingElse() {
        assertEquals("01.03.2026", dateInputText("2026-03-01", Language.GERMAN))
        assertEquals("01.03.2026", dateInputText("2026-03-01", Language.ENGLISH))
        assertEquals("2026年03月01日", dateInputText("2026-03-01", Language.CHINESE))
        assertEquals("01.03.2026", dateInputText("2026-03-01T10:15", Language.GERMAN))
        assertEquals("", dateInputText("", Language.GERMAN))
        assertEquals("31.02.2026", dateInputText("31.02.2026", Language.GERMAN))
    }

    @Test
    fun blankOrIsoDateIsWhatAnOptionalDateFieldMayHoldAtSave() {
        assertTrue(isBlankOrIsoDate(""))
        assertTrue(isBlankOrIsoDate("2026-03-01"))
        assertFalse(isBlankOrIsoDate("01.03.2026"))
        assertFalse(isBlankOrIsoDate("2026-02-30"))
    }
}
