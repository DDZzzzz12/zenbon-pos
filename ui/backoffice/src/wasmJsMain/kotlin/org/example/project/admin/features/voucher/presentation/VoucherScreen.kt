package org.example.project.admin.features.voucher.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.sections.VouchersSection
import org.example.project.admin.features.voucher.presentation.components.RedemptionRowsTable
import org.example.project.admin.features.voucher.presentation.components.VoucherDetailDrawer
import org.example.project.admin.features.voucher.presentation.components.VoucherKpiRow
import org.example.project.admin.features.voucher.presentation.components.VoucherRowsTable
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.employee.presentation.EmployeePickerViewModel
import org.example.project.features.employee.presentation.pickerLabel
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.components.PeriodStepper
import org.example.project.features.reports.presentation.components.PeriodStepperCompact
import org.example.project.features.reports.presentation.formatAnchor
import org.example.project.features.reports.presentation.isCurrentPeriod
import org.example.project.features.reports.presentation.stepped
import org.example.project.ui.auth.CapabilityGate
import org.example.project.ui.auth.holds
import org.example.project.ui.components.KpiSummaryItem
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosKpiSummary
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosSearchBar
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.employee.EmployeePicker
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterFieldGroup
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.closeWhenEmpty
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.kpiSummaryText
import org.example.project.ui.components.posSideSheetOverlays
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val DRAWER_WIDTH = 380.dp

/** Below this column width the four voucher views become a dropdown instead of a cut tab row. */
private val VOUCHER_TAB_ROW_MIN = 760.dp

private val VOUCHER_TABS = listOf(VoucherTab.ISSUED, VoucherTab.ACTIVE, VoucherTab.REDEEMED, VoucherTab.SETTINGS)

/**
 * The Gutscheine back office: period-scoped issued/redeemed lists, a global active list, global
 * search by number, split period/lifetime KPIs and a slide-in detail drawer. Read-only — voucher
 * creation is fiscal and stays on the device side.
 *
 * Filters follow the Zenbon filter pattern for a time-filtered screen: on a phone one row (compact
 * period stepper + Filter button) with granularity, employee and search in the full-screen panel;
 * wider, search · period stepper · Filter in the bar and only the employee in the side panel.
 *
 * The config section is the fourth view; it is not moved, only rendered here.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun VoucherScreen(viewModel: VoucherViewModel = koinViewModel()) {
    val state = viewModel.state
    // Loaded from here, not the ViewModel's init: adminApp shares one ViewModelStoreOwner, so the VM
    // survives logout and an init-load would show the previous session's data.
    LaunchedEffect(Unit) { viewModel.load(state.granularity, state.anchor) }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.VouchersTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(state.granularity, state.anchor) },
    )

    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    val listScroll = rememberScrollState()
    val screenPadding = LocalPosLayout.current.screenPadding
    // Above every layout branch below (drawer pushed or overlaid, search in the bar or the panel), so
    // a resize that switches one keeps an open filter panel and its draft.
    val filterPanel = rememberPosFilterPanelState(VoucherPanelFilter.of(state), FilterPanelKeys.VOUCHERS)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Pane logic stays on content width; the filter pattern reads LocalPosLayout (window width).
        val phone = maxWidth < PaneBreakpoints.PHONE_MAX
        val selected = state.selected
        val windowWidth =
            with(LocalDensity.current) {
                LocalWindowInfo.current.containerSize.width
                    .toDp()
            }
        // Below 1100dp the detail is a modal sheet over the page; wider windows keep the pushed pane.
        val asSheet = !phone && posSideSheetOverlays(windowWidth)
        // Decided on the PAGE width (this box): a pushed drawer shares the row, so opening a voucher
        // does not move search out of the bar.
        val searchInPanel =
            VoucherBarLayout.searchInPanel(
                compact = LocalPosLayout.current.isCompact,
                pageWidth = maxWidth,
                screenPadding = screenPadding,
            )
        Row(Modifier.fillMaxSize()) {
            // On a phone the open detail takes the whole screen; its close button is the way back.
            if (!(phone && selected != null)) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(listScroll).padding(vertical = PosSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
                ) {
                    AdminPageTitle(str(AdminStringKeys.VouchersTitle), Modifier.padding(horizontal = screenPadding))

                    // Hidden entirely on the config view: search results are the first branch of the
                    // content `when` below, so a search typed here would replace the config pane while
                    // the view switch is simultaneously disabled — leaving clearing the search as the
                    // only way back.
                    if (state.tab != VoucherTab.SETTINGS) {
                        VoucherFilters(state, viewModel, today, searchInPanel, filterPanel)
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = screenPadding),
                        verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
                    ) {
                        VoucherKpis(state)
                        VoucherViewSwitch(state, viewModel)
                        VoucherResults(state, viewModel)
                    }
                }
            }

            selected?.takeIf { !asSheet }?.let {
                VoucherDetailDrawer(
                    voucher = it,
                    history = state.history,
                    isHistoryLoading = state.isHistoryLoading,
                    currencyCode = state.currencyCode,
                    onClose = viewModel::closeDrawer,
                    modifier = if (phone) Modifier.fillMaxWidth() else Modifier.width(DRAWER_WIDTH),
                )
            }
        }

        selected?.takeIf { asSheet }?.let {
            VoucherDetailDrawer(
                voucher = it,
                history = state.history,
                isHistoryLoading = state.isHistoryLoading,
                currencyCode = state.currencyCode,
                onClose = viewModel::closeDrawer,
                asSheet = true,
            )
        }

        VoucherFilterPanel(state, viewModel, today, searchInPanel, filterPanel)

        if (state.isLoading) {
            PosLoadingOverlay(title = str(AdminStringKeys.VouchersTitle))
        }
    }
}

/**
 * The filter bar. The panel ([VoucherFilterPanel]) is composed by the screen, above its layout
 * branches; the bar only opens it.
 */
@Composable
private fun VoucherFilters(
    state: VoucherUiState,
    viewModel: VoucherViewModel,
    today: LocalDate,
    searchInPanel: Boolean,
    filterPanel: PosFilterPanelState<VoucherPanelFilter>,
) {
    val compact = LocalPosLayout.current.isCompact
    val screenPadding = LocalPosLayout.current.screenPadding
    val canPickEmployee = canPickVoucherEmployee()
    val employeeChip = appliedEmployeeChip(state.employeeId, canPickEmployee, compact) { viewModel.selectEmployee(null) }
    // Wider, the panel holds the employee picker (and maybe search): it may have nothing in it.
    val hasPanel = searchInPanel || canPickEmployee

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val barWidth = maxWidth - screenPadding * 2
        PosFilterBar(
            appliedFilters = listOfNotNull(employeeChip),
            search =
                if (searchInPanel) {
                    null
                } else {
                    PosFilterSearch(state.searchTerm, viewModel::onSearchTermChange, str(VoucherManagementKeys.searchVouchers))
                },
            resultText = voucherResultText(state),
            period = {
                VoucherPeriod(state, viewModel, today, compact, VoucherBarLayout.periodMaxWidth(barWidth, hasPanel, searchInPanel))
            },
            periodIsPrimary = true,
            activeFilterCount =
                VoucherFilterSummary.activeCount(state.employeeId, canPickEmployee, state.searchTerm, searchInPanel),
            onOpenPanel = if (hasPanel) filterPanel::open else null,
            // Clear-all clears the panel's filters, and search only where search is one of them (the
            // panel): a search typed into the bar is never cleared by it (PosFilterBar contract).
            onClearAll = {
                viewModel.selectEmployee(null)
                if (searchInPanel) viewModel.clearSearch()
            },
        )
    }
}

/**
 * Unlike Analytics, this destination stays on VOUCHER_REPORT — a cashier legitimately manages
 * vouchers. Only the "whose rollups" control needs the reporting capability, and without the gate it
 * failed SILENTLY: the voucher routes ignore filterEmployeeId rather than refusing it, so picking a
 * colleague showed your own vouchers under their name.
 */
@Composable
private fun canPickVoucherEmployee(): Boolean = holds(Capability.REPORT_ALL_READ)

/**
 * The panel, editing [filterPanel]'s draft: only its apply button reaches the view model, closing
 * it drops the draft. A field the draft changed stays shown when a resize moves it back into the bar
 * ([VoucherPanelFilter.showsSearch]), and is applied from there.
 */
@Composable
private fun VoucherFilterPanel(
    state: VoucherUiState,
    viewModel: VoucherViewModel,
    today: LocalDate,
    searchInPanel: Boolean,
    filterPanel: PosFilterPanelState<VoucherPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = VoucherPanelFilter.of(state)
    val draft = filterPanel.draft
    val showSearch = draft.value.showsSearch(searchInPanel, applied)
    val showGranularity = draft.value.showsGranularity(compact, applied)
    // Without REPORT_ALL_READ the employee picker is gated away: widened past the phone, with no
    // field drafted, nothing is left to show.
    if (filterPanel.closeWhenEmpty(showGranularity || showSearch || canPickVoucherEmployee())) return
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft { target -> applyPanelFilter(target, state, viewModel, today) } },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared(clearSearch = showSearch) },
                applyReset = {
                    viewModel.selectEmployee(null)
                    if (searchInPanel) viewModel.clearSearch()
                },
            )
        },
    ) {
        VoucherPanelFields(
            draft = draft,
            tab = state.tab,
            showGranularity = showGranularity,
            showSearch = showSearch,
        )
    }
}

/**
 * The applied employee filter as a removable chip (shown by the bar on wider windows only). The name
 * comes from the shared picker roster, written exactly as the picker shows it; while the roster is
 * not loaded (or the employee is no longer in it) the chip says only "Mitarbeiter", which is still
 * true — a staff filter is applied — without inventing a name.
 */
@Composable
private fun appliedEmployeeChip(
    employeeId: String?,
    canPickEmployee: Boolean,
    compact: Boolean,
    onRemove: () -> Unit,
): PosFilterChipItem? {
    if (!canPickEmployee) return null
    val roster: EmployeePickerViewModel = koinViewModel()
    // The bar shows applied chips on wider windows only: a compact window never loads the roster for
    // a chip it does not show (the panel's own picker loads it when opened). Growing the window loads it.
    LaunchedEffect(employeeId, compact) { if (employeeId != null && !compact) roster.load(activeOnly = true) }
    if (compact || employeeId == null) return null
    val name =
        roster.state.employees
            .firstOrNull { it.id == employeeId }
            ?.pickerLabel()
    val label = if (name != null) str(AdminStringKeys.VouchersEmployeeChip, name) else str(VoucherManagementKeys.employee)
    return PosFilterChipItem(label = label, onClick = onRemove)
}

@Composable
private fun voucherResultText(state: VoucherUiState): String =
    if (state.isSearchActive) {
        // Search spans every period: the line says so, since the period stepper is greyed meanwhile.
        filterResultLine(
            state.searchResults.size,
            AdminStringKeys.VouchersSearchHitsOne,
            AdminStringKeys.VouchersSearchHits,
            hasMore = state.searchHasMore,
        )
    } else {
        // Aktive is paged: "50+" rather than a page size that reads like the total.
        val (count, hasMore) =
            when (state.tab) {
                VoucherTab.ISSUED -> state.issued.size to false
                VoucherTab.ACTIVE -> state.active.size to state.activeHasMore
                VoucherTab.REDEEMED -> state.redeemed.size to false
                VoucherTab.SETTINGS -> 0 to false
            }
        filterResultLine(count, AdminStringKeys.VouchersResultCountOne, AdminStringKeys.VouchersResultCount, hasMore)
    }

@Composable
private fun VoucherPeriod(
    state: VoucherUiState,
    viewModel: VoucherViewModel,
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
            enabled = state.filtersApply,
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
                enabled = state.filtersApply,
                onGranularity = { g -> viewModel.load(g, minOf(state.anchor, today)) },
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                onPickPeriod = onPickPeriod,
            )
        }
    }
}

/**
 * The panel body. [showGranularity] on a phone only (wider, the full stepper in the bar has it);
 * [showSearch] wherever the bar has no room for the search field. Period and employee only filter
 * the two date-range views and are ignored by search, so they are greyed exactly when
 * [VoucherUiState.filtersApply] would be false for the draft.
 */
@Composable
private fun VoucherPanelFields(
    draft: PosFilterDraft<VoucherPanelFilter>,
    tab: VoucherTab,
    showGranularity: Boolean,
    showSearch: Boolean,
) {
    val filter = draft.value
    val periodFilters = (tab == VoucherTab.ISSUED || tab == VoucherTab.REDEEMED) && filter.searchTerm.isBlank()
    if (showGranularity) {
        PosFilterFieldGroup(str(PosPatternKeys.filterPeriod)) {
            PosSegmentedButtonRow(
                options = ReportsGranularity.reporting,
                selectedOption = filter.granularity,
                onOptionSelected = { draft.value = filter.copy(granularity = it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = periodFilters,
                optionLabel = { str(it.labelKey) },
            )
        }
    }
    CapabilityGate(Capability.REPORT_ALL_READ) {
        EmployeePicker(
            selectedEmployeeId = filter.employeeId,
            onEmployeeSelected = { draft.value = draft.value.copy(employeeId = it) },
            modifier = Modifier.fillMaxWidth(),
            enabled = periodFilters,
        )
    }
    if (showSearch) {
        PosSearchBar(
            query = filter.searchTerm,
            onQueryChange = { draft.value = draft.value.copy(searchTerm = it) },
            placeholder = str(VoucherManagementKeys.searchVouchers),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Hands the panel draft to the view model: one load for period + employee, then the search when the
 * draft changed it (wherever search sits now: a resize may have moved it back into the bar).
 */
private fun applyPanelFilter(
    draft: VoucherPanelFilter,
    state: VoucherUiState,
    viewModel: VoucherViewModel,
    today: LocalDate,
) {
    if (draft.granularity != state.granularity) {
        viewModel.load(draft.granularity, minOf(state.anchor, today), draft.employeeId)
    } else {
        viewModel.selectEmployee(draft.employeeId)
    }
    draft.searchToApply(VoucherPanelFilter.of(state))?.let(viewModel::onSearchTermChange)
}

/**
 * Wide: the four KPI cards. Phone: a one-line summary of the two PERIOD figures that expands to the
 * same cards — the lifetime ("gesamt") figures appear only there, with their caption.
 */
@Composable
private fun VoucherKpis(state: VoucherUiState) {
    val issued = issuedTotals(state.issued)
    val redeemed = redeemedTotals(state.redeemed)
    val cards: @Composable () -> Unit = {
        VoucherKpiRow(
            issuedAmount = issued.amount.toPlainString(),
            issuedCount = issued.count,
            redeemedAmount = redeemed.amount.toPlainString(),
            redeemedCount = redeemed.count,
            statistics = state.statistics,
            currencyCode = state.currencyCode,
        )
    }
    if (!LocalPosLayout.current.isCompact) {
        cards()
        return
    }
    val periodFigures =
        kpiSummaryText(
            listOf(
                KpiSummaryItem(
                    str(AdminStringKeys.VouchersKpiSummaryIssued),
                    formatMoneyForDisplay(issued.amount.toPlainString(), state.currencyCode),
                ),
                KpiSummaryItem(
                    str(AdminStringKeys.VouchersKpiSummaryRedeemed),
                    formatMoneyForDisplay(redeemed.amount.toPlainString(), state.currencyCode),
                ),
            ),
        )
    PosKpiSummary(summary = str(AdminStringKeys.VouchersKpiSummaryPeriod, periodFigures), content = cards)
}

/**
 * The four views: a tab row where the labels fit, otherwise a dropdown naming the current view
 * (never wrapping chips or a scrolling strip). Decided on the width this column really has, the
 * pushed drawer counted. Disabled while a search replaces the view.
 */
@Composable
private fun VoucherViewSwitch(
    state: VoucherUiState,
    viewModel: VoucherViewModel,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < VOUCHER_TAB_ROW_MIN) {
            VoucherViewDropdown(
                selected = state.tab,
                enabled = !state.isSearchActive,
                onSelect = viewModel::selectTab,
            )
        } else {
            PrimaryTabRow(selectedTabIndex = VOUCHER_TABS.indexOf(state.tab)) {
                VOUCHER_TABS.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        enabled = !state.isSearchActive,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tabLabel(tab), softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoucherViewDropdown(
    selected: VoucherTab,
    enabled: Boolean,
    onSelect: (VoucherTab) -> Unit,
) {
    PosFilterPicker(label = str(AdminStringKeys.VouchersViewLabel), value = tabLabel(selected), enabled = enabled) { close ->
        VOUCHER_TABS.forEach { tab ->
            PosDropdownItem(
                text = tabLabel(tab),
                selected = tab == selected,
                minHeight = adminTouchSize(40.dp),
                onClick = {
                    close()
                    onSelect(tab)
                },
            )
        }
    }
}

@Composable
private fun VoucherResults(
    state: VoucherUiState,
    viewModel: VoucherViewModel,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            when {
                state.isSearchActive ->
                    ResultTable(
                        vouchers = state.searchResults,
                        currencyCode = state.currencyCode,
                        onSelect = viewModel::select,
                        hasMore = state.searchHasMore,
                        isLoadingMore = state.isLoadingMore,
                        onLoadMore = viewModel::loadMoreSearch,
                    )
                state.tab == VoucherTab.ISSUED ->
                    // created-by-date returns the whole period unpaged, so no "load more".
                    ResultTable(
                        vouchers = state.issued,
                        currencyCode = state.currencyCode,
                        onSelect = viewModel::select,
                        hasMore = false,
                        isLoadingMore = false,
                        onLoadMore = {},
                    )
                state.tab == VoucherTab.ACTIVE ->
                    ResultTable(
                        vouchers = state.active,
                        currencyCode = state.currencyCode,
                        onSelect = viewModel::select,
                        hasMore = state.activeHasMore,
                        isLoadingMore = state.isLoadingMore,
                        onLoadMore = viewModel::loadMoreActive,
                    )
                state.tab == VoucherTab.REDEEMED ->
                    if (state.redeemed.isEmpty()) {
                        EmptyRow()
                    } else {
                        RedemptionRowsTable(
                            redemptions = state.redeemed,
                            currencyCode = state.currencyCode,
                            onSelectVoucherNumber = viewModel::selectByNumber,
                        )
                    }
                else -> VouchersSection()
            }
        }
    }
}

@Composable
private fun tabLabel(tab: VoucherTab): String =
    when (tab) {
        VoucherTab.ISSUED -> str(VoucherManagementKeys.createdVouchers)
        VoucherTab.ACTIVE -> str(VoucherManagementKeys.activeVouchers)
        VoucherTab.REDEEMED -> str(VoucherManagementKeys.redeemedVouchers)
        VoucherTab.SETTINGS -> str(AdminStringKeys.NavSettings)
    }

@Composable
private fun ResultTable(
    vouchers: List<VoucherDto.VoucherResponseDto>,
    currencyCode: String,
    onSelect: (VoucherDto.VoucherResponseDto) -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (vouchers.isEmpty()) {
        EmptyRow()
        return
    }
    VoucherRowsTable(vouchers = vouchers, currencyCode = currencyCode, onSelect = onSelect)
    if (hasMore) {
        PosTextButton(
            text = if (isLoadingMore) str(CommonKeys.loading) else str(AdminStringKeys.VouchersLoadMore),
            enabled = !isLoadingMore,
            onClick = onLoadMore,
        )
    }
}

@Composable
private fun EmptyRow() {
    Text(
        text = str(AdminStringKeys.VouchersNoResults),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
