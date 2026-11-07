/**
 * Custom single date picker dialog component.
 *
 * Provides a Material 3 date picker dialog for selecting a single date.
 * Used for transaction dates, document dates, and other single date selections.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onDateSelected Callback when date is selected with LocalDate
 * @param language Current app language for localized strings
 * @param initialDate Optional initial date to display (defaults to today)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.presentation.components.picker

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
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosTextButton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Custom single date picker dialog.
 *
 * Shows Material 3 date picker for selecting a single date.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun CustomDatePickerDialog(
    onDismiss: () -> Unit,
    onDateSelected: (date: LocalDate) -> Unit,
    language: Language,
    initialDate: LocalDate? = null,
) {
    // Calculate initial date in milliseconds
    // UTC start of day, as Material counts it (see PickerMillis.kt): the browser zone put the
    // initial selection one day early east of UTC.
    val initialDateMillis =
        remember(initialDate) {
            (
                initialDate ?: Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date
            ).toPickerMillis()
        }

    // Month names, weekday letters and the month/year header follow the app language, not the
    // platform locale (the till's reservation screens use this dialog too).
    val datePickerState =
        remember(language) {
            DatePickerState(locale = calendarLocaleFor(language), initialSelectedDateMillis = initialDateMillis)
        }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PosTextButton(
                text = getConfirmButtonText(language),
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(pickerMillisToDate(millis))
                    }
                    onDismiss()
                },
                enabled = datePickerState.selectedDateMillis != null,
            )
        },
        dismissButton = {
            PosTextButton(text = getCancelButtonText(language), onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Title
            Text(
                text = getDialogTitle(language),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )

            // Date Picker
            DatePicker(
                state = datePickerState,
                title = null,
                headline = {
                    // Show selected date or instruction
                    datePickerState.selectedDateMillis?.let { millis ->
                        Text(
                            text = formatPickerHeadline(pickerMillisToDate(millis), language),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } ?: Text(
                        text = getSelectDateInstruction(language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
                showModeToggle = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Get localized dialog title.
 */
private fun getDialogTitle(language: Language): String =
    when (language) {
        Language.GERMAN -> "Datum auswählen"
        Language.ENGLISH -> "Select Date"
        Language.CHINESE -> "选择日期"
    }

/**
 * Get localized instruction text.
 */
private fun getSelectDateInstruction(language: Language): String =
    when (language) {
        Language.GERMAN -> "Bitte wählen Sie ein Datum"
        Language.ENGLISH -> "Please select a date"
        Language.CHINESE -> "请选择日期"
    }

/**
 * Get localized confirm button text.
 */
private fun getConfirmButtonText(language: Language): String =
    when (language) {
        Language.GERMAN -> "OK"
        Language.ENGLISH -> "OK"
        Language.CHINESE -> "确定"
    }

/**
 * Get localized cancel button text.
 */
private fun getCancelButtonText(language: Language): String =
    when (language) {
        Language.GERMAN -> "Abbrechen"
        Language.ENGLISH -> "Cancel"
        Language.CHINESE -> "取消"
    }

/**
 * The picker headline: the same short date every date field shows (`13.10.2026` in German and English,
 * `2026年10月13日` in Chinese), from the one shared formatter rather than a picker-only copy that had
 * drifted to `10/13/2026` and an unpadded `2026年3月5日` (UI-P1-08 / SYS-19).
 */
internal fun formatPickerHeadline(
    date: LocalDate,
    language: Language,
): String = date.toString().formatShortDate(language)
