package org.example.project.core.presentation.components.picker

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.datetime.LocalDate
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The calendar follows the UI language, not the platform locale: Material's own formatter, given the
 * locale this module builds, names the month in the UI language whatever the machine runs in.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CalendarLanguageTest {
    private val october = LocalDate(2026, 10, 15).toPickerMillis()

    private fun monthYear(language: Language): String = DatePickerDefaults.dateFormatter().formatMonthYear(october, calendarLocaleFor(language)).orEmpty()

    @Test
    fun `each UI language maps to its own tag`() {
        assertEquals("de-DE", calendarLanguageTag(Language.GERMAN))
        assertEquals("en-DE", calendarLanguageTag(Language.ENGLISH))
        assertEquals("zh-DE", calendarLanguageTag(Language.CHINESE))
    }

    @Test
    fun `the month header is in the UI language`() {
        assertTrue(monthYear(Language.GERMAN).contains("Oktober"), monthYear(Language.GERMAN))
        assertTrue(monthYear(Language.ENGLISH).contains("October"), monthYear(Language.ENGLISH))
        // The browser (Intl) writes 2026年10月; Compose Desktop's own formatter 十月 2026. Both Chinese.
        val chinese = monthYear(Language.CHINESE)
        assertTrue(chinese.contains("10月") || chinese.contains("十月"), chinese)
    }
}
