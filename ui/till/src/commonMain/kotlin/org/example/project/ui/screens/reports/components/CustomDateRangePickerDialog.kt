/**
 * Custom date range picker dialog component.
 *
 * Provides a Material 3 date range picker dialog for selecting custom date ranges.
 * Users can select start and end dates for filtering reports data.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onDateRangeSelected Callback when date range is selected with start and end dates
 * @param language Current app language for localized strings
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.reports.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextButton
import kotlin.time.Instant

/**
 * Custom date range picker dialog.
 *
 * Shows Material 3 date range picker for selecting custom start and end dates.
 */
@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun CustomDateRangePickerDialog(
    onDismiss: () -> Unit,
    onDateRangeSelected: (startDate: LocalDate, endDate: LocalDate) -> Unit,
    language: Language,
) {
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    val dateRangePickerState = rememberDateRangePickerState()

    // Track selected dates from the picker
    LaunchedEffect(dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis) {
        val start = dateRangePickerState.selectedStartDateMillis
        val end = dateRangePickerState.selectedEndDateMillis

        if (start != null) {
            startDate =
                Instant
                    .fromEpochMilliseconds(start)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
        }
        if (end != null) {
            endDate =
                Instant
                    .fromEpochMilliseconds(end)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
        }

        if (start != null && end != null) {
            onDateRangeSelected(startDate!!, endDate!!)
            onDismiss()
        }
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            PosTextButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            // Title
            Text(
                text = str(ReportsKeys.selectDateRange),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )

            // Date Range Picker
            DateRangePicker(
                state = dateRangePickerState,
                title = null,
                headline = {
                    // Show selected range or instruction
                    if (startDate != null && endDate != null) {
                        Text(
                            text = "$startDate - $endDate",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = str(ReportsKeys.selectStartAndEndDate),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                },
                showModeToggle = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
