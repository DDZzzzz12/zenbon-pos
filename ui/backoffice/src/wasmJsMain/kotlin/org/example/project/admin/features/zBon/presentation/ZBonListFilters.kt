package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.components.PeriodStepper
import org.example.project.features.reports.presentation.components.PeriodStepperCompact
import org.example.project.features.reports.presentation.formatAnchor
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.closeWhenEmpty
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.theme.LocalPosLayout

/**
 * The Z-Bon list filter bar (Zenbon filter pattern, period-filtered screen, no search): the period
 * stepper is the primary control. On a phone it is `‹ Oktober 2026 ›` + the Filter button, with the
 * granularity (Tag / Woche / Monat / Jahr) in the panel; wider, the full period stepper alone in the
 * bar and no panel at all (a panel there would be empty).
 *
 * The panel ([ZBonFilterPanel]) is composed by the screen, above its layout branches.
 */
@Composable
internal fun ZBonFilterBar(
    state: ZBonListState,
    viewModel: ZBonListViewModel,
    today: LocalDate,
    filterPanel: PosFilterPanelState<ZBonPanelFilter>,
) {
    val compact = LocalPosLayout.current.isCompact
    PosFilterBar(
        search = null,
        resultText = filterResultLine(state.zBons.size, AdminStringKeys.ZBonListResultCountOne, AdminStringKeys.ZBonListResultCount),
        period = { ZBonPeriod(state, viewModel, today, compact) },
        periodIsPrimary = true,
        activeFilterCount = ZBonPanelFilter.of(state).activeCount(),
        // The panel holds only the granularity, which wider windows show in the bar's own stepper.
        onOpenPanel = if (compact) filterPanel::open else null,
    )
}

/**
 * The panel, editing [filterPanel]'s draft: only its apply button reaches the view model, closing it
 * drops the draft. A granularity the draft changed stays shown when a resize widens the window.
 */
@Composable
internal fun ZBonFilterPanel(
    state: ZBonListState,
    viewModel: ZBonListViewModel,
    today: LocalDate,
    filterPanel: PosFilterPanelState<ZBonPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = ZBonPanelFilter.of(state)
    val draft = filterPanel.draft
    // Widened past the point where the granularity is back in the bar, with nothing drafted.
    if (filterPanel.closeWhenEmpty(draft.value.showsGranularity(compact, applied))) return
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = {
            filterPanel.applyDraft { target ->
                target.granularityToApply(applied)?.let { viewModel.load(it, minOf(state.anchor, today)) }
            }
        },
        // The period is not a filter that can be cleared: no reset button.
        onReset = null,
    ) {
        PosFilterFieldGroup(str(PosPatternKeys.filterPeriod)) {
            PosSegmentedButtonRow(
                options = ReportsGranularity.reporting,
                selectedOption = draft.value.granularity,
                onOptionSelected = { draft.value = draft.value.copy(granularity = it) },
                modifier = Modifier.fillMaxWidth(),
                optionLabel = { str(it.labelKey) },
            )
        }
    }
}

@Composable
private fun ZBonPeriod(
    state: ZBonListState,
    viewModel: ZBonListViewModel,
    today: LocalDate,
    compact: Boolean,
) {
    val label = state.granularity.formatAnchor(state.anchor, LocalAppLanguage.current)
    val canStepForward = !state.granularity.isCurrentPeriod(state.anchor, today)
    val onStepBack = { viewModel.load(state.granularity, state.granularity.stepped(state.anchor, -1)) }
    val onStepForward = { viewModel.load(state.granularity, state.granularity.stepped(state.anchor, +1)) }
    val onPickPeriod = { anchor: LocalDate -> viewModel.load(state.granularity, anchor) }
    if (compact) {
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
        Box(Modifier.width(IntrinsicSize.Max)) {
            PeriodStepper(
                granularity = state.granularity,
                anchor = state.anchor,
                label = label,
                today = today,
                canStepForward = canStepForward,
                onGranularity = { g -> viewModel.load(g, minOf(state.anchor, today)) },
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                onPickPeriod = onPickPeriod,
            )
        }
    }
}
