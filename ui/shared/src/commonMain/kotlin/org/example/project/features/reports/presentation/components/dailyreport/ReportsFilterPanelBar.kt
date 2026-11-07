package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.employee.presentation.pickerLabel
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSwitchRow
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The back office's daily-report filter bar ([DailyReportFilterPresentation.Panel]): the Zenbon
 * `PosFilterBar` with the date-preset chip as its primary control and a Filter button opening
 * [panelState]; [ReportsFilterPanel] draws the panel itself. Wider windows show one removable chip
 * per applied panel filter. The gates are the till's ([DailyReportPanelGates]).
 *
 * The footer says "Anwenden" (no live result count): a count of the draft would need a view-model
 * change, which this layout work does not make.
 */
@Suppress("LongParameterList") // the bar's own inputs, grouped as far as they belong together
@Composable
internal fun ReportsFilterPanelBar(
    applied: DailyReportPanelFilter,
    gates: DailyReportPanelGates,
    actions: DailyReportPanelActions,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    panelState: PosFilterPanelState<DailyReportPanelFilter>,
    period: @Composable () -> Unit,
    resultText: String,
    modifier: Modifier = Modifier,
) {
    val effective = gates.effective(applied)
    PosFilterBar(
        search = null,
        resultText = resultText,
        modifier = modifier,
        period = period,
        periodIsPrimary = true,
        appliedFilters = appliedChips(effective, availableEmployees) { target -> actions.apply(applied, target(applied)) },
        activeFilterCount =
            dailyReportActiveFilterCount(
                employeeId = effective.employeeId,
                trainingMode = effective.trainingMode,
                sort = effective.sort,
                defaultSort = DailyReportPanelFilter.DEFAULT_SORT,
                paymentTypes = effective.paymentTypes,
            ),
        onOpenPanel = panelState::open,
        // Clears the panel's filters only: this bar has no search, and the period is not a panel field.
        onClearAll = { actions.apply(applied, gates.cleared(applied)) },
    )
}

/**
 * The daily report's `PosFilterPanel`: employee, sort, payment type and training, edited in
 * [panelState]'s draft that reaches the view model only through the apply button. Composed by the
 * caller outside the layout branch that moves the bar, so a resize keeps it open.
 */
@Suppress("LongParameterList") // the panel's own inputs, grouped as far as they belong together
@Composable
internal fun ReportsFilterPanel(
    panelState: PosFilterPanelState<DailyReportPanelFilter>,
    applied: DailyReportPanelFilter,
    gates: DailyReportPanelGates,
    actions: DailyReportPanelActions,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    availablePaymentTypes: List<String>,
) {
    if (!panelState.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val draft = panelState.draft
    PosFilterPanel(
        onDismiss = panelState::close,
        onApply = { panelState.applyDraft { target -> actions.apply(applied, target) } },
        onReset = {
            panelState.reset(
                compact = compact,
                clearDraft = { gates.cleared(applied) },
                applyReset = { actions.apply(applied, gates.cleared(applied)) },
            )
        },
    ) {
        if (gates.canReportShopWide) EmployeeField(draft, availableEmployees)
        if (gates.sortShown) SortField(draft)
        if (availablePaymentTypes.isNotEmpty()) PaymentTypeField(draft, availablePaymentTypes)
        if (gates.trainingShown) {
            PosFilterSwitchRow(
                label = str(StatisticsDashboardKeys.trainingMode),
                checked = draft.value.trainingMode,
                onCheckedChange = { draft.value = draft.value.copy(trainingMode = it) },
            )
        }
    }
}

/** The view switch on its own line under the bar: filling the width on a phone, at its own width wider. */
@Composable
internal fun PanelViewSwitch(
    availableViewModes: List<DashboardViewMode>,
    selectedViewMode: DashboardViewMode,
    onViewModeChanged: (DashboardViewMode) -> Unit,
) {
    val layout = LocalPosLayout.current
    Box(Modifier.fillMaxWidth().padding(horizontal = layout.screenPadding, vertical = PosSpacing.md)) {
        PosSegmentedButtonRow(
            options = availableViewModes,
            selectedOption = selectedViewMode,
            onOptionSelected = onViewModeChanged,
            // 48dp touch target on a phone; wider windows keep the row's own height.
            modifier = if (layout.isCompact) Modifier.heightIn(min = PosSpacing.xxxxl) else Modifier,
        ) { mode -> getViewModeLabel(mode) }
    }
}

/**
 * One removable chip per applied panel filter (the bar shows them on wider windows only). [remove]
 * takes how to drop the chip's filter from the applied one.
 */
@Composable
private fun appliedChips(
    effective: DailyReportPanelFilter,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
    remove: ((DailyReportPanelFilter) -> DailyReportPanelFilter) -> Unit,
): List<PosFilterChipItem> {
    val chips = mutableListOf<PosFilterChipItem>()
    effective.employeeId?.let { id ->
        val name = availableEmployees.firstOrNull { it.id == id }?.pickerLabel()
        val label = if (name != null) str(StatisticsDashboardKeys.chipEmployee, name) else str(StatisticsDashboardKeys.employeesLabel)
        chips += PosFilterChipItem(label = label, onClick = { remove { it.copy(employeeId = null) } })
    }
    if (effective.trainingMode) {
        chips +=
            PosFilterChipItem(
                label = str(StatisticsDashboardKeys.trainingMode),
                onClick = { remove { it.copy(trainingMode = false) } },
            )
    }
    if (effective.sort != DailyReportPanelFilter.DEFAULT_SORT) {
        chips +=
            PosFilterChipItem(
                label = str(StatisticsDashboardKeys.chipSort, getSortLabel(effective.sort)),
                onClick = { remove { it.copy(sort = DailyReportPanelFilter.DEFAULT_SORT) } },
            )
    }
    if (effective.paymentTypes.isNotEmpty()) {
        chips +=
            PosFilterChipItem(
                label = str(StatisticsDashboardKeys.chipPaymentTypes, effective.paymentTypes.size),
                onClick = { remove { it.copy(paymentTypes = emptySet()) } },
            )
    }
    return chips
}

@Composable
private fun EmployeeField(
    draft: PosFilterDraft<DailyReportPanelFilter>,
    availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto>,
) {
    val selectedId = draft.value.employeeId
    val value =
        if (selectedId == null) {
            str(EmployeeHubKeys.allEmployees)
        } else {
            availableEmployees.firstOrNull { it.id == selectedId }?.pickerLabel() ?: selectedId
        }
    PosFilterPicker(label = str(StatisticsDashboardKeys.employeesLabel), value = value) { close ->
        PosDropdownItem(
            text = str(EmployeeHubKeys.allEmployees),
            selected = selectedId == null,
            onClick = {
                close()
                draft.value = draft.value.copy(employeeId = null)
            },
        )
        availableEmployees.forEach { emp ->
            PosDropdownItem(
                text = emp.pickerLabel(),
                selected = emp.id == selectedId,
                onClick = {
                    close()
                    draft.value = draft.value.copy(employeeId = emp.id)
                },
            )
        }
    }
}

@Composable
private fun SortField(draft: PosFilterDraft<DailyReportPanelFilter>) {
    PosFilterPicker(label = str(StatisticsDashboardKeys.sortLabel), value = getSortLabel(draft.value.sort)) { close ->
        StatisticsDto.StatisticsSortOption.entries.forEach { sort ->
            PosDropdownItem(
                text = getSortLabel(sort),
                selected = sort == draft.value.sort,
                onClick = {
                    close()
                    draft.value = draft.value.copy(sort = sort)
                },
            )
        }
    }
}

/** Multi-select: no chip selected means every payment type, as in the till's dropdown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentTypeField(
    draft: PosFilterDraft<DailyReportPanelFilter>,
    availablePaymentTypes: List<String>,
) {
    val selected = draft.value.paymentTypes
    PosFilterFieldGroup(str(StatisticsDashboardKeys.paymentTypeFilter)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        ) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { draft.value = draft.value.copy(paymentTypes = emptySet()) },
                label = { Text(str(StatisticsDashboardKeys.allPaymentTypes)) },
            )
            availablePaymentTypes.forEach { type ->
                val isSelected = type in selected
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        draft.value = draft.value.copy(paymentTypes = if (isSelected) selected - type else selected + type)
                    },
                    label = { Text(type) },
                )
            }
        }
    }
}
