package org.example.project.admin.ui

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.picker.calendarLocaleFor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * In the browser (headless Chrome, whose own locale is en-US) the calendar header follows the UI
 * language handed to the picker, not the browser: German UI reads "Oktober", Chinese UI "10月".
 * On wasm Material formats through `Intl` with the locale it is given, so this is the real path.
 */
@OptIn(ExperimentalMaterial3Api::class)
class CalendarLanguageBrowserTest {
    private val october = LocalDate(2026, 10, 15).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun monthYear(language: Language): String = DatePickerDefaults.dateFormatter().formatMonthYear(october, calendarLocaleFor(language)).orEmpty()

    @Test
    fun germanUiShowsGermanMonthNamesInAnyBrowser() {
        assertTrue(monthYear(Language.GERMAN).contains("Oktober"), monthYear(Language.GERMAN))
    }

    @Test
    fun chineseUiShowsChineseMonthNames() {
        assertTrue(monthYear(Language.CHINESE).contains("10月"), monthYear(Language.CHINESE))
    }

    @Test
    fun englishUiShowsEnglishMonthNames() {
        assertTrue(monthYear(Language.ENGLISH).contains("October"), monthYear(Language.ENGLISH))
    }
}
