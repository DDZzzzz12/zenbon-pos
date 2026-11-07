package org.example.project.ui.screens.settings.components

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class EmptyHistoryDescriptionTest {
    @Test
    fun `no extra filter names only the date range`() {
        assertEquals(PrinterSettingsKeys.noHistoryRangeDescription, emptyHistoryDescriptionKey(null, null, null))
    }

    @Test
    fun `any status type or location filter picks the filtered sentence`() {
        assertEquals(
            PrinterSettingsKeys.noHistoryFilteredDescription,
            emptyHistoryDescriptionKey(PrinterDto.PrintJobStatus.FAILED, null, null),
        )
        assertEquals(
            PrinterSettingsKeys.noHistoryFilteredDescription,
            emptyHistoryDescriptionKey(null, PrinterDto.PrintType.BILL, null),
        )
        assertEquals(PrinterSettingsKeys.noHistoryFilteredDescription, emptyHistoryDescriptionKey(null, null, 3L))
    }
}
