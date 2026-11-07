/**
 * Date filter selector component for print job history.
 *
 * Provides predefined date ranges (Today, Yesterday, Last 7 days, etc.)
 * and a custom date range picker. Similar to the date filtering in Reports screen
 * (ZBon panel and Kassenbuch panel).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Date range options for filtering.
 */
enum class DateRangeOption {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    CUSTOM,
}

/**
 * Date range data class.
 */
data class DateRange(
    val start: LocalDateTime,
    val end: LocalDateTime,
)

/**
 * Date filter selector with predefined ranges and custom option.
 *
 * @param selectedRange Currently selected date range option
 * @param onRangeSelected Callback when date range is selected
 * @param language Current app language
 * @param modifier Optional modifier
 */
@Composable
fun DateFilterSelector(
    selectedRange: DateRangeOption,
    onRangeSelected: (DateRangeOption, DateRange) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Dropdown button
        PosSecondaryButton(
            text = getDateRangeLabel(selectedRange, language),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )

        // Dropdown menu
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DateRangeOption.entries.forEach { option ->
                PosDropdownItem(
                    text = getDateRangeLabel(option, language),
                    selected = selectedRange == option,
                    onClick = {
                        expanded = false
                        if (option == DateRangeOption.CUSTOM) {
                            showCustomDatePicker = true
                        } else {
                            val range = calculateDateRange(option)
                            onRangeSelected(option, range)
                        }
                    },
                )
            }
        }
    }

    // Custom date range picker dialog
    if (showCustomDatePicker) {
        CustomDateRangeDialog(
            language = language,
            onDismiss = { showCustomDatePicker = false },
            onConfirm = { startDate, endDate ->
                showCustomDatePicker = false
                onRangeSelected(
                    DateRangeOption.CUSTOM,
                    DateRange(startDate, endDate),
                )
            },
        )
    }
}

/**
 * Get localized label for date range option.
 */
internal fun getDateRangeLabel(
    option: DateRangeOption,
    language: Language,
): String =
    when (option) {
        DateRangeOption.TODAY ->
            when (language) {
                Language.GERMAN -> "Heute"
                Language.ENGLISH -> "Today"
                Language.CHINESE -> "今天"
            }
        DateRangeOption.YESTERDAY ->
            when (language) {
                Language.GERMAN -> "Gestern"
                Language.ENGLISH -> "Yesterday"
                Language.CHINESE -> "昨天"
            }
        DateRangeOption.LAST_7_DAYS ->
            when (language) {
                Language.GERMAN -> "Letzte 7 Tage"
                Language.ENGLISH -> "Last 7 Days"
                Language.CHINESE -> "最近7天"
            }
        DateRangeOption.LAST_30_DAYS ->
            when (language) {
                Language.GERMAN -> "Letzte 30 Tage"
                Language.ENGLISH -> "Last 30 Days"
                Language.CHINESE -> "最近30天"
            }
        DateRangeOption.CUSTOM ->
            when (language) {
                Language.GERMAN -> "Benutzerdefiniert"
                Language.ENGLISH -> "Custom Range"
                Language.CHINESE -> "自定义范围"
            }
    }

/**
 * Calculate date range based on predefined option.
 */
@OptIn(ExperimentalTime::class)
private fun calculateDateRange(option: DateRangeOption): DateRange {
    val now = Clock.System.now()
    val timezone = TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(timezone)

    // Start of today (00:00:00)
    val startOfToday =
        LocalDateTime(
            today.year,
            today.month,
            today.day,
            0,
            0,
            0,
        )

    // End of today (23:59:59)
    val endOfToday =
        LocalDateTime(
            today.year,
            today.month,
            today.day,
            23,
            59,
            59,
        )

    return when (option) {
        DateRangeOption.TODAY -> {
            DateRange(startOfToday, endOfToday)
        }
        DateRangeOption.YESTERDAY -> {
            val yesterday = startOfToday.date.minus(1, DateTimeUnit.DAY)
            DateRange(
                LocalDateTime(yesterday, LocalTime(0, 0, 0)),
                LocalDateTime(yesterday, LocalTime(23, 59, 59)),
            )
        }
        DateRangeOption.LAST_7_DAYS -> {
            val sevenDaysAgo = startOfToday.date.minus(7, DateTimeUnit.DAY)
            DateRange(
                LocalDateTime(sevenDaysAgo, LocalTime(0, 0, 0)),
                endOfToday,
            )
        }
        DateRangeOption.LAST_30_DAYS -> {
            val thirtyDaysAgo = startOfToday.date.minus(30, DateTimeUnit.DAY)
            DateRange(
                LocalDateTime(thirtyDaysAgo, LocalTime(0, 0, 0)),
                endOfToday,
            )
        }
        DateRangeOption.CUSTOM -> {
            // Default to today for custom (will be overridden by dialog)
            DateRange(startOfToday, endOfToday)
        }
    }
}

/**
 * Custom date range picker dialog.
 *
 * Allows user to select custom start and end dates.
 */
@Composable
private fun CustomDateRangeDialog(
    language: Language,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime, LocalDateTime) -> Unit,
) {
    // TODO: Implement date picker
    // For now, show a simple placeholder dialog
    PosDialog(
        onDismiss = onDismiss,
        title = getDateRangeLabel(DateRangeOption.CUSTOM, language),
        showCloseButton = false,
        footer = {
            androidx.compose.foundation.layout
                .Spacer(
                    androidx.compose.ui.Modifier
                        .weight(1f),
                )
            PosTextButton(text = getString(CommonKeys.cancel, language), onClick = onDismiss)
        },
    ) {
        Text(customRangePlaceholder(language))
    }
}

/** The custom range picker is not built yet; say so in the viewer's language. */
private fun customRangePlaceholder(language: Language): String =
    when (language) {
        Language.GERMAN -> "Benutzerdefinierter Zeitraum – folgt in Kürze"
        Language.ENGLISH -> "Custom date range picker - Coming soon"
        Language.CHINESE -> "自定义日期范围选择器 – 敬请期待"
    }
