package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.employee.presentation.pickerLabel
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.filter.PosFilterPanelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsFilterBar(
    selectedPreset: DateFilterPreset,
    onPresetSelected: (DateFilterPreset) -> Unit,
    onCustomRangeClick: () -> Unit,
    selectedViewMode: DashboardViewMode,
    onViewModeChanged: (DashboardViewMode) -> Unit,
    canReportShopWide: Boolean,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    selectedEmployeeId: String?,
    onEmployeeSelected: (String?) -> Unit,
    selectedSort: StatisticsDto.StatisticsSortOption,
    onSortChanged: (StatisticsDto.StatisticsSortOption) -> Unit,
    availableViewModes: List<DashboardViewMode>,
    isCompact: Boolean,
    trainingMode: Boolean = false,
    onToggleTrainingMode: (() -> Unit)? = null,
    availablePaymentTypes: List<String> = emptyList(),
    selectedPaymentTypes: Set<String> = emptySet(),
    onPaymentTypesChanged: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    horizontalInset: Dp? = null,
    presentation: DailyReportFilterPresentation = DailyReportFilterPresentation.Scroller,
    // The result line of the [DailyReportFilterPresentation.Panel] bar ("12 Rechnungen"); unused by Scroller.
    resultText: String = "",
    // The [DailyReportFilterPresentation.Panel] panel's state, hoisted by the caller that composes the
    // panel itself ([ReportsFilterPanel]) above its layout branches; required for Panel, unused by Scroller.
    filterPanelState: PosFilterPanelState<DailyReportPanelFilter>? = null,
) {
    if (presentation == DailyReportFilterPresentation.Panel) {
        val applied = DailyReportPanelFilter(selectedEmployeeId, trainingMode, selectedSort, selectedPaymentTypes)
        val gates = DailyReportPanelGates.of(canReportShopWide, selectedViewMode, trainingShown = onToggleTrainingMode != null)
        val actions = DailyReportPanelActions(onEmployeeSelected, onToggleTrainingMode, onSortChanged, onPaymentTypesChanged)
        val panelState = requireNotNull(filterPanelState) { "The Panel presentation needs the caller's filterPanelState" }
        ReportsFilterPanelBar(
            applied = applied,
            gates = gates,
            actions = actions,
            availableEmployees = availableEmployees,
            panelState = panelState,
            period = {
                // Same gate as the till's chip: the period is a manager+ choice.
                if (canReportShopWide) {
                    DateFilterChip(
                        selectedPreset = selectedPreset,
                        onPresetSelected = onPresetSelected,
                        onCustomRangeClick = onCustomRangeClick,
                    )
                }
            },
            resultText = resultText,
            modifier = modifier,
        )
        PanelViewSwitch(availableViewModes, selectedViewMode, onViewModeChanged)
        return
    }

    val inset = horizontalInset ?: if (isCompact) 8.dp else 16.dp
    val chips: @Composable () -> Unit = {
        FilterChips(
            selectedPreset = selectedPreset,
            onPresetSelected = onPresetSelected,
            onCustomRangeClick = onCustomRangeClick,
            selectedViewMode = selectedViewMode,
            canReportShopWide = canReportShopWide,
            availableEmployees = availableEmployees,
            selectedEmployeeId = selectedEmployeeId,
            onEmployeeSelected = onEmployeeSelected,
            selectedSort = selectedSort,
            onSortChanged = onSortChanged,
            trainingMode = trainingMode,
            onToggleTrainingMode = onToggleTrainingMode,
            availablePaymentTypes = availablePaymentTypes,
            selectedPaymentTypes = selectedPaymentTypes,
            onPaymentTypesChanged = onPaymentTypesChanged,
        )
    }

    if (isCompact) {
        // Compact: two rows, scrollable chips then segmented buttons
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = inset, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PosHorizontalScroller(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { chips() }

            PosSegmentedButtonRow(
                options = availableViewModes,
                selectedOption = selectedViewMode,
                onOptionSelected = onViewModeChanged,
            ) { mode -> getViewModeLabel(mode) }
        }
        return
    }

    // Wrapping row: the chips and the view-mode control flow together, the control last, and it drops
    // onto its own line when it does not fit. (A PosHorizontalScroller here could not be scrolled with
    // the mouse wheel, and clipped its last chip.)
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = inset, vertical = if (isCompact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        chips()
        PosSegmentedButtonRow(
            options = availableViewModes,
            selectedOption = selectedViewMode,
            onOptionSelected = onViewModeChanged,
        ) { mode -> getViewModeLabel(mode) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(
    selectedPreset: DateFilterPreset,
    onPresetSelected: (DateFilterPreset) -> Unit,
    onCustomRangeClick: () -> Unit,
    selectedViewMode: DashboardViewMode,
    canReportShopWide: Boolean,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    selectedEmployeeId: String?,
    onEmployeeSelected: (String?) -> Unit,
    selectedSort: StatisticsDto.StatisticsSortOption,
    onSortChanged: (StatisticsDto.StatisticsSortOption) -> Unit,
    trainingMode: Boolean,
    onToggleTrainingMode: (() -> Unit)?,
    availablePaymentTypes: List<String>,
    selectedPaymentTypes: Set<String>,
    onPaymentTypesChanged: (Set<String>) -> Unit,
) {
    // Date filter chip (manager+ only)
    if (canReportShopWide) {
        DateFilterChip(
            selectedPreset = selectedPreset,
            onPresetSelected = onPresetSelected,
            onCustomRangeClick = onCustomRangeClick,
        )
    }

    // Employee filter chip (manager+ only)
    if (canReportShopWide) {
        EmployeeFilterChip(
            availableEmployees = availableEmployees,
            selectedEmployeeId = selectedEmployeeId,
            onEmployeeSelected = onEmployeeSelected,
        )
    }

    // Training mode chip (manager+ only)
    if (onToggleTrainingMode != null) {
        FilterChip(
            selected = trainingMode,
            onClick = onToggleTrainingMode,
            label = { Text(str(StatisticsDashboardKeys.trainingMode), style = MaterialTheme.typography.labelMedium) },
        )
    }

    // Sort chip (hidden for TABLES: no server-side sort)
    if (selectedViewMode != DashboardViewMode.TABLES) {
        SortFilterChip(
            selectedSort = selectedSort,
            onSortChanged = onSortChanged,
        )
    }

    // Payment type filter chip
    if (availablePaymentTypes.isNotEmpty()) {
        PaymentTypeFilterChip(
            availablePaymentTypes = availablePaymentTypes,
            selectedPaymentTypes = selectedPaymentTypes,
            onPaymentTypesChanged = onPaymentTypesChanged,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateFilterChip(
    selectedPreset: DateFilterPreset,
    onPresetSelected: (DateFilterPreset) -> Unit,
    onCustomRangeClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(getPresetLabel(selectedPreset), style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            modifier = Modifier,
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DateFilterPreset.entries.forEach { preset ->
                PosDropdownItem(
                    text = getPresetLabel(preset),
                    selected = preset == selectedPreset,
                    onClick = {
                        expanded = false
                        if (preset == DateFilterPreset.CUSTOM) {
                            onCustomRangeClick()
                        } else {
                            onPresetSelected(preset)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeeFilterChip(
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    selectedEmployeeId: String?,
    onEmployeeSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selectedEmployeeId != null,
            onClick = { expanded = true },
            label = {
                val label =
                    if (selectedEmployeeId != null) {
                        availableEmployees.find { it.id == selectedEmployeeId }?.employeeNumber ?: selectedEmployeeId
                    } else {
                        str(StatisticsDashboardKeys.employeesLabel)
                    }
                Text(label, style = MaterialTheme.typography.labelMedium)
            },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            modifier = Modifier,
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PosDropdownItem(
                text = str(EmployeeHubKeys.allEmployees),
                selected = selectedEmployeeId == null,
                onClick = {
                    expanded = false
                    onEmployeeSelected(null)
                },
            )
            availableEmployees.forEach { emp ->
                PosDropdownItem(
                    text = emp.pickerLabel(),
                    selected = emp.id == selectedEmployeeId,
                    onClick = {
                        expanded = false
                        onEmployeeSelected(emp.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFilterChip(
    selectedSort: StatisticsDto.StatisticsSortOption,
    onSortChanged: (StatisticsDto.StatisticsSortOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selectedSort != StatisticsDto.StatisticsSortOption.RECENT,
            onClick = { expanded = true },
            label = { Text(getSortLabel(selectedSort), style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
            modifier = Modifier,
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            StatisticsDto.StatisticsSortOption.entries.forEach { sort ->
                PosDropdownItem(
                    text = getSortLabel(sort),
                    selected = sort == selectedSort,
                    onClick = {
                        expanded = false
                        onSortChanged(sort)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentTypeFilterChip(
    availablePaymentTypes: List<String>,
    selectedPaymentTypes: Set<String>,
    onPaymentTypesChanged: (Set<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selectedPaymentTypes.isNotEmpty(),
            onClick = { expanded = true },
            label = {
                val label =
                    if (selectedPaymentTypes.isNotEmpty()) {
                        "${str(StatisticsDashboardKeys.paymentTypeFilter)} (${selectedPaymentTypes.size})"
                    } else {
                        str(StatisticsDashboardKeys.paymentTypeFilter)
                    }
                Text(label, style = MaterialTheme.typography.labelMedium)
            },
            leadingIcon = { Icon(Icons.Default.Payment, contentDescription = null) },
            modifier = Modifier,
        )

        PosDropdown(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PosDropdownItem(
                text = str(StatisticsDashboardKeys.allPaymentTypes),
                selected = selectedPaymentTypes.isEmpty(),
                onClick = {
                    expanded = false
                    onPaymentTypesChanged(emptySet())
                },
            )
            availablePaymentTypes.forEach { pt ->
                val isSelected = pt in selectedPaymentTypes
                PosDropdownItem(
                    text = pt,
                    selected = isSelected,
                    onClick = {
                        val newSelection =
                            if (isSelected) {
                                selectedPaymentTypes - pt
                            } else {
                                selectedPaymentTypes + pt
                            }
                        onPaymentTypesChanged(newSelection)
                    },
                )
            }
        }
    }
}

@Composable
internal fun getViewModeLabel(mode: DashboardViewMode): String =
    when (mode) {
        DashboardViewMode.BILLS -> str(StatisticsDashboardKeys.billsLabel)
        DashboardViewMode.TABLES -> str(StatisticsDashboardKeys.tablesLabel)
        DashboardViewMode.EMPLOYEES -> str(StatisticsDashboardKeys.employeesLabel)
    }

@Composable
private fun getPresetLabel(preset: DateFilterPreset): String =
    when (preset) {
        DateFilterPreset.SINCE_LAST_ZBON -> str(StatisticsDashboardKeys.sinceLastZBon)
        DateFilterPreset.TODAY -> str(StatisticsDashboardKeys.today)
        DateFilterPreset.YESTERDAY -> str(StatisticsDashboardKeys.yesterday)
        DateFilterPreset.THIS_WEEK -> str(StatisticsDashboardKeys.thisWeek)
        DateFilterPreset.THIS_MONTH -> str(StatisticsDashboardKeys.thisMonth)
        DateFilterPreset.CUSTOM -> str(StatisticsDashboardKeys.customRange)
    }

@Composable
internal fun getSortLabel(sort: StatisticsDto.StatisticsSortOption): String =
    when (sort) {
        StatisticsDto.StatisticsSortOption.RECENT -> str(StatisticsDashboardKeys.sortRecent)
        StatisticsDto.StatisticsSortOption.OLDEST -> str(StatisticsDashboardKeys.sortOldest)
        StatisticsDto.StatisticsSortOption.HIGHEST -> str(StatisticsDashboardKeys.sortHighest)
        StatisticsDto.StatisticsSortOption.LOWEST -> str(StatisticsDashboardKeys.sortLowest)
    }
