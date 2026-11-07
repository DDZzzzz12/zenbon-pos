package org.example.project.admin.core.presentation.format

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayDayTest {
    @Test
    fun isoDateTimeRendersAsGermanShortDate() {
        assertEquals("01.03.2026", "2026-03-01T00:00".asDisplayDay(Language.GERMAN))
    }

    @Test
    fun isoDateRendersPerLanguage() {
        assertEquals("15.01.2026", "2026-01-15".asDisplayDay(Language.ENGLISH))
        assertEquals("2026年01月15日", "2026-01-15".asDisplayDay(Language.CHINESE))
    }

    @Test
    fun missingDateIsBlank() {
        assertEquals("", null.asDisplayDay(Language.GERMAN))
        assertEquals("", "  ".asDisplayDay(Language.GERMAN))
    }
}
