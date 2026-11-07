package org.example.project.core.presentation.components.picker

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** The picker headline is the date fields' short date (UI-P1-08): one format per language, padded. */
class PickerHeadlineTest {
    private val october = LocalDate(2026, 10, 13)
    private val singleDigit = LocalDate(2026, 3, 5)

    @Test
    fun `german headline uses the month number not the enum name`() {
        assertEquals("13.10.2026", formatPickerHeadline(october, Language.GERMAN))
        assertEquals("05.03.2026", formatPickerHeadline(singleDigit, Language.GERMAN))
    }

    @Test
    fun `english headline matches the date field, dd MM yyyy`() {
        assertEquals("13.10.2026", formatPickerHeadline(october, Language.ENGLISH))
        assertEquals("05.03.2026", formatPickerHeadline(singleDigit, Language.ENGLISH))
    }

    @Test
    fun `chinese headline uses year month day characters, zero padded`() {
        assertEquals("2026年10月13日", formatPickerHeadline(october, Language.CHINESE))
        assertEquals("2026年03月05日", formatPickerHeadline(singleDigit, Language.CHINESE))
    }
}
