package org.example.project.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.isCompact
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.PosSpacing

@Composable
fun FilterBar(
    selectedDateRange: DateRange,
    customDateRange: Pair<String, String>?,
    onDateRangeSelected: (DateRange) -> Unit,
    onCustomRangeClick: () -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    searchTerm: String? = null,
    onSearchTermChanged: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
) {
    val windowSize = LocalWindowSizeClass.current
    val hasSearch = searchTerm != null && onSearchTermChanged != null
    var isSearchExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Auto-expand if searchTerm is non-blank
    LaunchedEffect(searchTerm) {
        if (!searchTerm.isNullOrBlank()) {
            isSearchExpanded = true
        }
    }

    // Auto-focus when search expands
    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            focusRequester.requestFocus()
        }
    }

    val (startDate, endDate) =
        if (selectedDateRange == DateRange.CUSTOM && customDateRange != null) {
            customDateRange
        } else {
            selectedDateRange.toDatePair()
        }

    val formattedStart = startDate.formatReadableDateTime(language, false)
    val formattedEnd = endDate.formatReadableDateTime(language, false)
    val isCompact = windowSize.isCompact()

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = PosSpacing.sm, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Search icon / expanded search field
            if (hasSearch) {
                if (isSearchExpanded) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )

                        BasicTextField(
                            value = searchTerm ?: "",
                            onValueChange = { onSearchTermChanged?.invoke(it) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .focusRequester(focusRequester),
                            textStyle =
                                LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            singleLine = true,
                            maxLines = 1,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions =
                                KeyboardActions(onDone = {
                                    if (searchTerm.isNullOrBlank()) {
                                        isSearchExpanded = false
                                    }
                                }),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchTerm.isNullOrBlank()) {
                                        Text(
                                            text = searchPlaceholder,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        PosIconButton(
                            icon = Icons.Default.Clear,
                            contentDescription = null,
                            onClick = {
                                onSearchTermChanged.invoke("")
                                isSearchExpanded = false
                            },
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            size = 32.dp,
                            iconSize = 18.dp,
                        )
                    }
                } else {
                    PosIconButton(
                        icon = Icons.Default.Search,
                        contentDescription = null,
                        onClick = { isSearchExpanded = true },
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        size = 32.dp,
                        iconSize = 20.dp,
                    )
                }
            }

            // Unified date range chip — hidden on compact when search is expanded
            if (!(isCompact && isSearchExpanded)) {
                FilterBarDateRangeChip(
                    selectedDateRange = selectedDateRange,
                    formattedStart = formattedStart,
                    formattedEnd = formattedEnd,
                    language = language,
                    isCompact = isCompact || isSearchExpanded,
                    onDateRangeSelected = onDateRangeSelected,
                    onCustomRangeClick = onCustomRangeClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBarDateRangeChip(
    selectedDateRange: DateRange,
    formattedStart: String,
    formattedEnd: String,
    language: Language,
    isCompact: Boolean,
    onDateRangeSelected: (DateRange) -> Unit,
    onCustomRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val chipLabel =
        if (isCompact) {
            "$formattedStart - $formattedEnd"
        } else {
            "$formattedStart - $formattedEnd · ${getDateRangeLabel(selectedDateRange, language)}"
        }

    Box(modifier) {
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
                        modifier = Modifier.size(16.dp),
                    )
                    PosAutoSizeText(
                        text = chipLabel,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Date range header in dropdown
            if (isCompact) {
                Text(
                    text = getDateRangeLabel(selectedDateRange, language),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
            }

            DateRange.entries.forEach { range ->
                PosDropdownItem(
                    text = getDateRangeLabel(range, language),
                    selected = selectedDateRange == range,
                    onClick = {
                        expanded = false
                        if (range == DateRange.CUSTOM) {
                            onCustomRangeClick()
                        } else {
                            onDateRangeSelected(range)
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
