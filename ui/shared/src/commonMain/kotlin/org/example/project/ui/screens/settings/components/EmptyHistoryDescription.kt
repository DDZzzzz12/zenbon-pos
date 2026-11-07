package org.example.project.ui.screens.settings.components

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys

/**
 * Picks the empty-state sentence of the print history. Both variants take the active date range
 * label as `{0}`, so an empty list never reads as "nothing was ever printed" when it is only the
 * filter that hides the jobs.
 */
fun emptyHistoryDescriptionKey(
    statusFilter: PrinterDto.PrintJobStatus?,
    printTypeFilter: PrinterDto.PrintType?,
    locationId: Long?,
): StringKey =
    if (statusFilter != null || printTypeFilter != null || locationId != null) {
        PrinterSettingsKeys.noHistoryFilteredDescription
    } else {
        PrinterSettingsKeys.noHistoryRangeDescription
    }
