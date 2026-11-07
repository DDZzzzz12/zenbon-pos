package org.example.project.features.reports.presentation.components.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton

/**
 * Date range selector component for filtering reports.
 *
 * Provides a responsive dropdown chip for selecting predefined date ranges (Today, Yesterday, etc.)
 * or opening a custom date range picker. Only visible for Manager+ users.
 * Adapts to screen size - shows full text on larger screens, icon only on compact screens.
 *
 * @param currentRange Currently selected date range
 * @param onRangeSelected Callback when a predefined range is selected
 * @param onCustomRangeClick Callback when custom range option is clicked
 * @param language Current app language for localized strings
 * @param isCompact Whether to show compact version (icon only)
 * @param modifier Optional modifier for the component
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@Composable
fun DateRangeSelectorComponent(
    modifier: Modifier = Modifier,
    currentRange: DateRange,
    onRangeSelected: (DateRange) -> Unit,
    onCustomRangeClick: () -> Unit,
    language: Language,
    isCompact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (isCompact) {
            // Icon-only button for compact screens
            PosIconButton(
                icon = Icons.Default.DateRange,
                contentDescription = str(ReportsKeys.selectDateRange),
                onClick = { expanded = true },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            // Full filter chip for larger screens
            FilterChip(
                selected = true,
                onClick = { expanded = true },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = getDateRangeLabel(currentRange, language),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
            )
        }

        // Dropdown menu with date range options
        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DateRange.values().forEach { range ->
                PosDropdownItem(
                    text = getDateRangeLabel(range, language),
                    selected = range == currentRange,
                    leadingIcon = Icons.Default.Check,
                    onClick = {
                        if (range == DateRange.CUSTOM) {
                            expanded = false
                            onCustomRangeClick()
                        } else {
                            onRangeSelected(range)
                            expanded = false
                        }
                    },
                )
            }
        }
    }
}

private fun getDateRangeLabel(
    range: DateRange,
    language: Language,
): String =
    when (range) {
        DateRange.TODAY -> getString(ReportsKeys.dateRangeToday, language)
        DateRange.YESTERDAY -> getString(ReportsKeys.dateRangeYesterday, language)
        DateRange.THIS_WEEK -> getString(ReportsKeys.dateRangeThisWeek, language)
        DateRange.LAST_WEEK -> getString(ReportsKeys.dateRangeLastWeek, language)
        DateRange.THIS_MONTH -> getString(ReportsKeys.dateRangeThisMonth, language)
        DateRange.LAST_3_MONTH -> getString(ReportsKeys.dateRangeLast3Months, language)
        DateRange.CUSTOM -> getString(ReportsKeys.dateRangeCustom, language)
    }
