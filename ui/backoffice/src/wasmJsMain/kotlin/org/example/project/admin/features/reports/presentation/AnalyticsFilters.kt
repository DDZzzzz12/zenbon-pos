package org.example.project.admin.features.reports.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.employee.presentation.pickerLabel
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.components.PeriodStepper
import org.example.project.features.reports.presentation.components.PeriodStepperCompact
import org.example.project.features.reports.presentation.formatAnchor
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.employee.EmployeePicker
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterBarLayout
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSwitchRow
import org.example.project.ui.components.filter.pluralResultKey
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Analysen filter bar (Zenbon filter pattern, period-filtered screen, no search): the period
 * stepper is the primary control — `‹ 21.10.2026 ›` + the Filter button on a phone, the full
 * stepper with its Tag/Woche/Monat/Jahr row + the labelled Filter button wider. Employee and
 * Vergleich live in the panel ([AnalyticsFilterPanel], composed by the screen above its layout
 * branches) on every width; wider windows show them as removable chips under the bar.
 *
 * The screen is reachable only with REPORT_ALL_READ (`AdminNavItem.ANALYTICS`), so the employee
 * picker carries no gate of its own, exactly as before; neither does the badge count.
 *
 * @param barWidth the bar's width without the screen gutters.
 */
@Composable
internal fun AnalyticsFilterBar(
    state: ReportsState,
    viewModel: AnalyticsViewModel,
    today: LocalDate,
    barWidth: Dp,
    filterPanel: PosFilterPanelState<AnalyticsPanelFilter>,
) {
    val applied = AnalyticsPanelFilter.of(state)
    PosFilterBar(
        search = null,
        resultText = analyticsResultText(state),
        period = {
            AnalyticsPeriod(
                state = state,
                viewModel = viewModel,
                today = today,
                wideMaxWidth = PosFilterBarLayout.periodMaxWidth(barWidth, searchMin = 0.dp),
            )
        },
        periodIsPrimary = true,
        appliedFilters = appliedChips(applied) { target -> applyAnalyticsFilter(target, state, viewModel, today) },
        activeFilterCount = applied.activeCount(),
        onOpenPanel = filterPanel::open,
        onClearAll = { applyAnalyticsFilter(applied.cleared(), state, viewModel, today) },
    )
}

/**
 * The panel, editing [filterPanel]'s draft: only its apply button reaches the view model, closing it
 * drops the draft. The granularity row is a panel field on a phone (or once the draft changed it).
 */
@Composable
internal fun AnalyticsFilterPanel(
    state: ReportsState,
    viewModel: AnalyticsViewModel,
    today: LocalDate,
    filterPanel: PosFilterPanelState<AnalyticsPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = AnalyticsPanelFilter.of(state)
    val draft = filterPanel.draft
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft { target -> applyAnalyticsFilter(target, state, viewModel, today) } },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared() },
                applyReset = { applyAnalyticsFilter(applied.cleared(), state, viewModel, today) },
            )
        },
    ) {
        val filter = draft.value
        if (filter.showsGranularity(compact, applied)) {
            PosFilterFieldGroup(str(PosPatternKeys.filterPeriod)) {
                PosSegmentedButtonRow(
                    options = ReportsGranularity.reporting,
                    selectedOption = filter.granularity,
                    onOptionSelected = { draft.value = draft.value.copy(granularity = it) },
                    modifier = Modifier.fillMaxWidth(),
                    optionLabel = { str(it.labelKey) },
                )
            }
        }
        EmployeePicker(
            selectedEmployeeId = filter.employeeId,
            onEmployeeSelected = { draft.value = draft.value.copy(employeeId = it) },
            modifier = Modifier.fillMaxWidth(),
        )
        PosFilterSwitchRow(
            label = str(AdminStringKeys.ReportsCompareToggle),
            checked = filter.compareEnabled,
            onCheckedChange = { draft.value = draft.value.copy(compareEnabled = it) },
        )
    }
}

/**
 * Hands a target filter to the view model: one `load` with every target value when the granularity
 * or employee changed, then `setCompareEnabled` when the comparison flips (its own reload then asks
 * for the same data, so no stale response can replace the right one). See [AnalyticsPanelFilter].
 */
private fun applyAnalyticsFilter(
    target: AnalyticsPanelFilter,
    state: ReportsState,
    viewModel: AnalyticsViewModel,
    today: LocalDate,
) {
    val applied = AnalyticsPanelFilter.of(state)
    if (target.needsReload(applied)) {
        viewModel.load(
            granularity = target.granularity,
            anchor = target.anchorFor(applied, state.anchor, today),
            compareEnabled = target.compareEnabled,
            employeeId = target.employeeId,
        )
    }
    target.compareToSet(applied)?.let(viewModel::setCompareEnabled)
}

/** "12 Rechnungen im Zeitraum"; the placeholder until the first response, never a made-up 0. */
@Composable
private fun analyticsResultText(state: ReportsState): String {
    val bills = state.overview?.totalBillCount ?: return str(AdminStringKeys.ReportsResultBills, EMPTY_VALUE)
    return str(pluralResultKey(bills, AdminStringKeys.ReportsResultBillsOne, AdminStringKeys.ReportsResultBills), bills.toString())
}

@Composable
private fun AnalyticsPeriod(
    state: ReportsState,
    viewModel: AnalyticsViewModel,
    today: LocalDate,
    wideMaxWidth: Dp,
) {
    val label = state.granularity.formatAnchor(state.anchor, LocalAppLanguage.current)
    // Forward is disabled once the anchor is the live period — you cannot report the future.
    val canStepForward = !state.granularity.isCurrentPeriod(state.anchor, today)
    val onStepBack = { viewModel.load(state.granularity, state.granularity.stepped(state.anchor, -1)) }
    val onStepForward = { viewModel.load(state.granularity, state.granularity.stepped(state.anchor, +1)) }
    // No minOf clamp here: the picker blocks the future, and the granularity never changes inside it.
    val onPickPeriod = { anchor: LocalDate -> viewModel.load(state.granularity, anchor) }
    if (LocalPosLayout.current.isCompact) {
        PeriodStepperCompact(
            label = label,
            canStepForward = canStepForward,
            onStepBack = onStepBack,
            onStepForward = onStepForward,
            onPickPeriod = onPickPeriod,
            anchor = state.anchor,
            granularity = state.granularity,
            today = today,
        )
    } else {
        // Its own width up to what the Filter button leaves; narrower, the granularity row wraps above.
        Box(Modifier.widthIn(max = wideMaxWidth).width(IntrinsicSize.Max)) {
            PeriodStepper(
                granularity = state.granularity,
                anchor = state.anchor,
                label = label,
                today = today,
                canStepForward = canStepForward,
                // Switching granularity keeps the date but clamps it to today, so the new period is
                // never in the future.
                onGranularity = { g -> viewModel.load(g, minOf(state.anchor, today)) },
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                onPickPeriod = onPickPeriod,
                granularities = ReportsGranularity.reporting,
            )
        }
    }
}

/**
 * One removable chip per applied panel filter (the bar shows them on wider windows only). The
 * employee's name comes from the shared picker roster, written as the picker shows it; while the
 * roster is not loaded the chip says only "Mitarbeiter", which is still true.
 */
@Composable
private fun appliedChips(
    applied: AnalyticsPanelFilter,
    apply: (AnalyticsPanelFilter) -> Unit,
): List<PosFilterChipItem> {
    val compact = LocalPosLayout.current.isCompact
    val roster: EmployeePickerViewModel = koinViewModel()
    // A compact window never loads the roster for a chip it does not show.
    LaunchedEffect(applied.employeeId, compact) { if (applied.employeeId != null && !compact) roster.load(activeOnly = true) }
    if (compact) return emptyList()
    val chips = mutableListOf<PosFilterChipItem>()
    applied.employeeId?.let { id ->
        val name =
            roster.state.employees
                .firstOrNull { it.id == id }
                ?.pickerLabel()
        val label = if (name != null) str(StatisticsDashboardKeys.chipEmployee, name) else str(VoucherManagementKeys.employee)
        chips += PosFilterChipItem(label = label, onClick = { apply(applied.copy(employeeId = null)) })
    }
    if (applied.compareEnabled) {
        chips +=
            PosFilterChipItem(
                label = str(AdminStringKeys.ReportsCompareToggle),
                onClick = { apply(applied.copy(compareEnabled = false)) },
            )
    }
    return chips
}
