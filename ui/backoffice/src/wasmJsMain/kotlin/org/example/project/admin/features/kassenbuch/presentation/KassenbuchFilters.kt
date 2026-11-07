package org.example.project.admin.features.kassenbuch.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.closeWhenEmpty
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.theme.LocalPosLayout

/**
 * The Kassenbuch filter bar (Zenbon filter pattern, period-filtered screen): the period stepper is
 * the primary control. On a phone it is `‹ Oktober 2026 ›` + the Filter button, with granularity and
 * search in the panel; wider, search · full period stepper in one row and no panel at all, unless the
 * bar is too narrow for both ([KassenbuchBarLayout.searchInPanel]): then search moves into the panel.
 *
 * The panel ([KassenbuchFilterPanel]) is composed by the screen, above its layout branches.
 *
 * @param barWidth the bar's width without the screen gutters.
 */
@Composable
internal fun KassenbuchFilterBar(
    state: KassenbuchState,
    viewModel: KassenbuchViewModel,
    today: LocalDate,
    barWidth: Dp,
    searchInPanel: Boolean,
    filterPanel: PosFilterPanelState<KassenbuchPanelFilter>,
) {
    val compact = LocalPosLayout.current.isCompact
    PosFilterBar(
        search =
            if (searchInPanel) {
                null
            } else {
                PosFilterSearch(state.filterText, viewModel::setFilterText, str(AdminStringKeys.KassenbuchSearchPlaceholder))
            },
        resultText = kassenbuchResultText(state),
        period = {
            KassenbuchPeriod(state, viewModel, today, compact, KassenbuchBarLayout.periodMaxWidth(barWidth, searchInPanel))
        },
        periodIsPrimary = true,
        appliedFilters = listOfNotNull(appliedSearchChip(state.filterText, searchInPanel, compact, viewModel)),
        activeFilterCount = KassenbuchPanelFilter.of(state).activeCount(searchInPanel),
        // The panel holds only what left the bar: with search in the bar there is nothing to open.
        onOpenPanel = if (searchInPanel) filterPanel::open else null,
        // Search is a panel filter only where it sits in the panel; a search in the bar is never cleared.
        onClearAll = if (searchInPanel) ({ viewModel.setFilterText("") }) else null,
    )
}

/**
 * The panel, editing [filterPanel]'s draft: only its apply button reaches the view model, closing it
 * drops the draft. A field the draft changed stays shown when a resize moves it back into the bar.
 */
@Composable
internal fun KassenbuchFilterPanel(
    state: KassenbuchState,
    viewModel: KassenbuchViewModel,
    today: LocalDate,
    searchInPanel: Boolean,
    filterPanel: PosFilterPanelState<KassenbuchPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = KassenbuchPanelFilter.of(state)
    val draft = filterPanel.draft
    val showGranularity = draft.value.showsGranularity(compact, applied)
    val showSearch = draft.value.showsSearch(searchInPanel, applied)
    // Widened past the point where both fields are back in the bar, with nothing drafted.
    if (filterPanel.closeWhenEmpty(showGranularity || showSearch)) return
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = {
            filterPanel.applyDraft { target ->
                target.granularityToApply(applied)?.let { viewModel.load(it, minOf(state.anchor, today)) }
                target.searchToApply(applied)?.let(viewModel::setFilterText)
            }
        },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared(clearSearch = showSearch) },
                applyReset = { if (searchInPanel) viewModel.setFilterText("") },
            )
        },
    ) {
        val filter = draft.value
        if (showGranularity) {
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
        if (showSearch) {
            PosSearchBar(
                query = filter.searchTerm,
                onQueryChange = { draft.value = draft.value.copy(searchTerm = it) },
                placeholder = str(AdminStringKeys.KassenbuchSearchPlaceholder),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The applied search as a removable chip, on wider windows whose bar had no room for the search
 * field (the bar shows applied chips there only; on a phone the result line carries the count).
 */
@Composable
private fun appliedSearchChip(
    filterText: String,
    searchInPanel: Boolean,
    compact: Boolean,
    viewModel: KassenbuchViewModel,
): PosFilterChipItem? {
    if (compact || !searchInPanel || filterText.isBlank()) return null
    return PosFilterChipItem(label = str(AdminStringKeys.KassenbuchSearchChip, filterText.trim()), onClick = { viewModel.setFilterText("") })
}

/**
 * "12 Buchungen" for the loaded period (after the client-side search), or the global-search hits.
 * The global search stops at the server's cap with no continuation, so a capped list reads "200+".
 */
@Composable
private fun kassenbuchResultText(state: KassenbuchState): String {
    val globalResults = state.globalResults
    return if (globalResults != null) {
        filterResultLine(
            globalResults.size,
            AdminStringKeys.KassenbuchGlobalResultCountOne,
            AdminStringKeys.KassenbuchGlobalResultCount,
            hasMore = state.globalResultsTruncated,
        )
    } else {
        filterResultLine(state.visibleEntries.size, AdminStringKeys.KassenbuchResultCountOne, AdminStringKeys.KassenbuchResultCount)
    }
}

@Composable
private fun KassenbuchPeriod(
    state: KassenbuchState,
    viewModel: KassenbuchViewModel,
    today: LocalDate,
    compact: Boolean,
    wideMaxWidth: Dp,
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
        // Its own width up to what the search field leaves; narrower, the granularity row wraps above.
        Box(Modifier.widthIn(max = wideMaxWidth).width(IntrinsicSize.Max)) {
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
