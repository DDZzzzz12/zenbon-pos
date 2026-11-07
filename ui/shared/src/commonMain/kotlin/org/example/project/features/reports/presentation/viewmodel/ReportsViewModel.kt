package org.example.project.features.reports.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.getZBonStartTime
import org.example.project.core.utils.sortEmployeesForPicker
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.features.reports.presentation.state.ReportsUiState
import org.example.project.features.reports.presentation.state.ReportsViewMode
import org.example.project.money.Decimal
import org.example.project.ui.components.BillDetailTrail
import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ViewModel for managing Reports screen state and business logic.
 *
 * Handles statistics fetching based on user role with split loading:
 * summary (KPI totals) + paginated detail per view mode.
 */
class ReportsViewModel(
    private val statisticsApiClient: StatisticsApiClient,
    private val zBonApiClient: ZBonApiClient,
    private val employeeApiClient: EmployeeApiClient,
    private val billApiClient: BillApiClient,
    private val paymentTypeApiClient: PaymentTypeApiClient,
    /** The till's clock — injectable so a test can stand the till at a day boundary. */
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    companion object {
        const val PAGE_SIZE = 20

        /** The server's page cap for the employee-group list, used to fetch the tip earners in few calls. */
        private const val TIP_EARNER_PAGE_SIZE = 100

        /** A safety stop for the tip-earner paging: 2 000 employees is not a shop. */
        private const val MAX_TIP_EARNER_PAGES = 20

        /** Index of the "Z-Bon Verlauf" tab; 0 is the daily report. */
        const val ZBON_HISTORY_TAB = 1

        /** The last instant of a day, to the microsecond the database keeps. */
        private val END_OF_DAY = LocalTime(23, 59, 59, 999_999_000)

        /** How long a burst of pushes from other devices is coalesced before the list is checked. */
        const val EXTERNAL_CHANGE_DEBOUNCE_MS = 1_000L

        /** The presets that run "up to now", so a bill written elsewhere after entry belongs to them. */
        private val OPEN_PRESETS =
            setOf(
                DateFilterPreset.SINCE_LAST_ZBON,
                DateFilterPreset.TODAY,
                DateFilterPreset.THIS_WEEK,
                DateFilterPreset.THIS_MONTH,
            )
    }

    private val logger = Logger.withTag("ReportsViewModel")

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private val _viewMode = MutableStateFlow(ReportsViewMode.CASHIER)
    val viewMode: StateFlow<ReportsViewMode> = _viewMode.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Current dashboard view mode (BILLS, TABLES, EMPLOYEES) */
    private val _dashboardViewMode = MutableStateFlow(DashboardViewMode.BILLS)
    val dashboardViewMode: StateFlow<DashboardViewMode> = _dashboardViewMode.asStateFlow()

    /** Current page number per view mode (0-indexed) */
    private val currentPages =
        mutableMapOf(
            DashboardViewMode.BILLS to 0,
            DashboardViewMode.TABLES to 0,
            DashboardViewMode.EMPLOYEES to 0,
        )

    /**
     * Bumped by every load that replaces the list (a range reload, a sort change). A page answered
     * for an older generation, or for a range that is no longer the stored one, is dropped: after a
     * bill mutation the list is reloaded over a recomputed range, and a page still in flight over
     * the old range must not land on top of it — that left the Berichte list missing the new
     * Storno/Neubuchung while the cards, read over the new range, already counted them.
     */
    private var listGeneration = 0

    /**
     * Bumped by every [loadStatistics]; the summary (KPI cards, payment breakdown) is applied only if
     * it was requested by the latest one. Separate from [listGeneration] because a sort change
     * replaces the list without re-reading the summary. A filter panel applies several fields in one
     * tap, each setter starting its own reload, and the earlier reload's answer — filtered by fewer
     * fields — could land last and leave the cards disagreeing with the list.
     */
    private var summaryGeneration = 0

    /** Set by a reload after a bill change; the first page that lands then asks the list to scroll to its top. */
    private var scrollToTopPending = false

    /** The pending check after a push from another device; a newer push restarts it. */
    private var externalChangeCheck: Job? = null

    /**
     * Choose the reporting view from what this principal may actually see.
     *
     * This selects a UI VARIANT — how much of the shop's reporting to render — and it is now the
     * same question the server asks. It used to be role-based, and the comment here said why: both
     * CASHIER and MANAGER hold `REPORT_READ`, so what separated them (own sales versus shop-wide)
     * had no capability to name, and the fix would be "a vocabulary addition with its own
     * migration, not a client refactor".
     *
     * `REPORT_ALL_READ` is that addition. `MANAGER_PLUS` now means "holds it", which is what the
     * server's `resolveTargetEmployee` decides scoping on — so the two cannot disagree, and an
     * AUDITOR carrying role CASHIER no longer gets the cashier's own-sales view of a shop they exist
     * to audit.
     *
     * The role's `else ->` error branch is gone with it. It existed to refuse TERMINAL_DEVICE, which
     * `PosNavDestination.REPORTS` already keeps off this screen via `REPORT_READ`.
     */
    fun determineViewMode(seesEveryone: Boolean) {
        viewModelScope.launch {
            _viewMode.value =
                if (seesEveryone) ReportsViewMode.MANAGER_PLUS else ReportsViewMode.CASHIER

            // For managers, enable employee selection and load employee list
            if (_viewMode.value == ReportsViewMode.MANAGER_PLUS) {
                _uiState.update { it.copy(canReportShopWide = true) }
                loadAvailableEmployees()
            }

            // Set default view mode for cashiers
            if (_viewMode.value == ReportsViewMode.CASHIER) {
                _dashboardViewMode.value = DashboardViewMode.BILLS
            }

            // Every principal on this screen holds REPORT_READ, and every seeded REPORT_READ group holds CATALOG_READ.
            loadAvailablePaymentTypes()

            // Load data after determining view mode. This runs on EVERY entry into Berichte, and the
            // VM may be a retained one (Verwaltung destinations save and restore their state), so the
            // range is recomputed for the preset the operator left selected — not reused, and not
            // reset to "since the last Z-Bon" under a chip that still says otherwise.
            reloadForSelectedPreset(serverStamp = null)
        }
    }

    /**
     * Load list of available employees for filtering.
     */
    private fun loadAvailableEmployees() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEmployees = true) }
            runCatchingApi { employeeApiClient.getEmployeeSummaries(active = true) }
                .onSuccess { employees ->
                    _uiState.update {
                        it.copy(availableEmployees = sortEmployeesForPicker(employees), isLoadingEmployees = false)
                    }
                }.onError {
                    _uiState.update { it.copy(isLoadingEmployees = false) }
                }
        }
    }

    /**
     * Load the active payment types for the filter dropdown.
     *
     * From `GET /api/payment-types?includeInactive=false` (`CATALOG_READ`), not
     * `GET /api/payment-types/active`: that one is `CATALOG_MANAGE` by design (`CatalogRules`), so it
     * refused every cashier, auditor and accountant who opened this screen, and the silent failure
     * below left their filter empty.
     */
    private fun loadAvailablePaymentTypes() {
        viewModelScope.launch {
            runCatchingApi { paymentTypeApiClient.getAllPaymentTypes(includeInactive = false) }
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(availablePaymentTypes = response.paymentTypes.map { pt -> pt.name })
                    }
                }.onError { /* silently fail — filter just won't be populated */ }
        }
    }

    /**
     * Handle payment type filter selection.
     *
     * @param types Set of payment type names to filter by, or empty for all
     */
    fun onPaymentTypesChanged(types: Set<String>) {
        _uiState.update { it.copy(selectedPaymentTypes = types) }
        resetAndReload()
    }

    /**
     * Toggle training mode on/off (manager+ only). Reloads all data.
     */
    fun toggleTrainingMode() {
        if (_viewMode.value != ReportsViewMode.MANAGER_PLUS) return
        _uiState.update { it.copy(trainingMode = !it.trainingMode) }
        resetAndReload()
    }

    fun onEmployeeSelected(employeeId: String?) {
        if (_viewMode.value != ReportsViewMode.MANAGER_PLUS) return
        _uiState.update { it.copy(selectedEmployeeId = employeeId) }
        resetAndReload()
    }

    /**
     * Handle dashboard view mode change. Triggers detail data reload only if data not already loaded.
     *
     * @param mode New view mode
     */
    fun onDashboardViewModeChanged(mode: DashboardViewMode) {
        _dashboardViewMode.value = mode
        val hasData =
            when (mode) {
                DashboardViewMode.BILLS -> _uiState.value.onlyBills.isNotEmpty()
                DashboardViewMode.TABLES -> _uiState.value.tableCards.isNotEmpty()
                DashboardViewMode.EMPLOYEES -> _uiState.value.employeeGroups.isNotEmpty()
            }
        if (!hasData) {
            currentPages[mode] = 0
            loadDetailData(mode, 0)
        }
    }

    /**
     * Load statistics: summary + detail for current view mode.
     */
    @OptIn(ExperimentalTime::class)
    fun loadStatistics(
        startTime: String? = null,
        endTime: String? = null,
    ) = loadStatistics(startTime, endTime, serverStamp = null)

    /**
     * [serverStamp] is set only by a reload after a bill change; it moves the end of an open range
     * ("up to now") past the Beleg the server just wrote — see [openRangeEnd].
     *
     * A load never selects a tab. It used to end in `selectTab(0)`, so every reload — a bill pushed
     * from another device, a retry, a Z-Bon just created — threw an operator reading the Z-Bon
     * history back to the daily report. Moving to the report is [showZBonPeriod]'s job alone.
     */
    @OptIn(ExperimentalTime::class)
    private fun loadStatistics(
        startTime: String?,
        endTime: String?,
        serverStamp: (suspend () -> LocalDateTime?)?,
    ) {
        logger.i { "ReportsViewModel.loadStatistics: loading statistics" }
        listGeneration++
        val summaryGen = ++summaryGeneration
        // Raised before the launch, so the scroll trigger cannot ask for a page over the old range
        // while the new one is still being computed (see loadMore).
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val (startDate, endDate) =
                    if (startTime != null && endTime != null) {
                        Pair(startTime, endTime)
                    } else {
                        val start = getZBonStartTime(zBonApiClient)
                        Pair(start, openRangeEnd(tillNow(), serverStamp))
                    }

                logger.d { "ReportsViewModel.loadStatistics: date range $startDate to $endDate" }
                _uiState.update { it.copy(timeRange = Pair(startDate, endDate)) }
                loadSummary(startDate, endDate, summaryGen)
                loadDetailData(_dashboardViewMode.value, 0)
            } catch (e: Exception) {
                logger.e(e) { "ReportsViewModel.loadStatistics: failed to load statistics" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Load lightweight summary data (KPI totals, payment breakdown).
     */
    private suspend fun loadSummary(
        startDate: String,
        endDate: String,
        generation: Int,
    ) {
        val filterEmployeeId =
            if (_viewMode.value == ReportsViewMode.MANAGER_PLUS) {
                _uiState.value.selectedEmployeeId
            } else {
                null
            }

        val result =
            runCatchingApi {
                statisticsApiClient.getSalesOverview(
                    startDate = startDate,
                    endDate = endDate,
                    employeeId = filterEmployeeId,
                    trainingOnly = _uiState.value.trainingMode,
                )
            }
        // A newer reload owns the cards now; this answer (success or failure) describes older filters.
        if (generation != summaryGeneration) {
            logger.d { "ReportsViewModel.loadSummary: dropping a stale summary" }
            return
        }
        result
            .onSuccess { data ->
                logger.i {
                    "ReportsViewModel.loadSummary: loaded summary, totalRevenue=${data.totalRevenue}, billCount=${data.totalBillCount}, currency=${data.currency}"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        // Formatted here, not left raw. These fields are displayed verbatim by the KPI
                        // cards and the bottom sheets, and the server sends bare decimals — so the
                        // report has been showing "0.00" with no currency at all, in both apps. The
                        // state's own defaults ("0,00 €") always claimed otherwise.
                        totalRevenue = formatMoneyForDisplay(data.totalRevenue, data.currency),
                        billedRevenue = formatMoneyForDisplay(data.billedRevenue, data.currency),
                        totalTips = formatMoneyForDisplay(data.totalTips, data.currency),
                        averageTipPerBill = averageTipPerBill(data.totalTips, data.totalBillCount, data.currency),
                        canceledRevenue = formatMoneyForDisplay(data.canceledRevenue, data.currency),
                        refundRevenue = formatMoneyForDisplay(data.refundRevenue, data.currency),
                        totalDiscounts = formatMoneyForDisplay(data.totalDiscounts, data.currency),
                        openItemAmount = formatMoneyForDisplay(data.openItemAmount, data.currency),
                        revenueByPaymentMethod = data.revenueByPaymentMethod,
                        receivableCleared =
                            data.receivableCleared
                                .takeIf { Decimal.parseOrNull(it)?.isZero == false }
                                ?.let { formatMoneyForDisplay(it, data.currency) },
                        totalBillCount = data.totalBillCount,
                        currency = data.currency,
                    )
                }
            }.onError { uiError ->
                logger.e { "ReportsViewModel.loadSummary: failed with error=$uiError" }
                _uiState.update { it.copy(isLoading = false, error = uiError) }
            }
    }

    /**
     * Load paginated detail data for the given view mode.
     *
     * @param mode Dashboard view mode to load data for
     * @param page Page number to load (0-indexed); page 0 replaces, page > 0 appends
     */
    private fun loadDetailData(
        mode: DashboardViewMode,
        page: Int = 0,
    ) {
        val generation = listGeneration
        viewModelScope.launch {
            if (page == 0) {
                _uiState.update { it.copy(isLoadingDetail = true) }
            } else {
                _uiState.update { it.copy(isLoadingMore = true) }
            }

            val timeRange = _uiState.value.timeRange ?: return@launch
            val filterEmployeeId =
                if (_viewMode.value == ReportsViewMode.MANAGER_PLUS) {
                    _uiState.value.selectedEmployeeId
                } else {
                    null
                }

            when (mode) {
                DashboardViewMode.BILLS -> loadBills(timeRange, filterEmployeeId, page, generation)
                DashboardViewMode.TABLES -> loadTableCards(timeRange, filterEmployeeId, page, generation)
                DashboardViewMode.EMPLOYEES -> loadEmployeeGroups(timeRange, filterEmployeeId, page, generation)
            }
        }
    }

    /**
     * Whether the first page now landing should scroll the list to its top (it is the reload after a
     * bill change); consumes the request so paging and later reloads do not scroll.
     */
    private fun takeScrollToTop(page: Int): Boolean {
        if (page != 0 || !scrollToTopPending) return false
        scrollToTopPending = false
        return true
    }

    /** Whether a page loaded under [generation] over [timeRange] still belongs to the list on screen. */
    private fun isCurrentLoad(
        generation: Int,
        timeRange: Pair<String, String>,
    ): Boolean = generation == listGeneration && timeRange == _uiState.value.timeRange

    private suspend fun loadBills(
        timeRange: Pair<String, String>,
        filterEmployeeId: String?,
        page: Int,
        generation: Int,
    ) {
        val paymentTypes =
            _uiState.value.selectedPaymentTypes
                .takeIf { it.isNotEmpty() }
                ?.toList()
        runCatchingApi {
            statisticsApiClient.getPaginatedBills(
                startDate = timeRange.first,
                endDate = timeRange.second,
                employeeId = filterEmployeeId,
                page = page,
                size = PAGE_SIZE,
                sort = _uiState.value.sortOption,
                trainingOnly = _uiState.value.trainingMode,
                paymentTypes = paymentTypes,
            )
        }.onSuccess { data ->
            if (!isCurrentLoad(generation, timeRange)) return@onSuccess
            val newBills = data.bills
            val pagination = data.pagination
            currentPages[DashboardViewMode.BILLS] = page
            val scrollToTop = takeScrollToTop(page)
            _uiState.update {
                it.copy(
                    scrollToTopRequest = it.scrollToTopRequest + if (scrollToTop) 1 else 0,
                    onlyBills = if (page == 0) newBills else it.onlyBills.appendPage(newBills) { bill -> bill.billId },
                    hasMoreBills = pagination.hasNext,
                    isLoadingDetail = false,
                    isLoadingMore = false,
                )
            }
        }.onError { uiError ->
            if (!isCurrentLoad(generation, timeRange)) return@onError
            if (page == 0) scrollToTopPending = false
            _uiState.update { it.copy(isLoadingDetail = false, isLoadingMore = false, error = uiError) }
        }
    }

    private suspend fun loadTableCards(
        timeRange: Pair<String, String>,
        filterEmployeeId: String?,
        page: Int,
        generation: Int,
    ) {
        val paymentTypes =
            _uiState.value.selectedPaymentTypes
                .takeIf { it.isNotEmpty() }
                ?.toList()
        runCatchingApi {
            statisticsApiClient.getPaginatedTableCards(
                startDate = timeRange.first,
                endDate = timeRange.second,
                employeeId = filterEmployeeId,
                page = page,
                size = PAGE_SIZE,
                trainingOnly = _uiState.value.trainingMode,
                paymentTypes = paymentTypes,
            )
        }.onSuccess { data ->
            if (!isCurrentLoad(generation, timeRange)) return@onSuccess
            val newCards = data.tableCards
            val pagination = data.pagination
            currentPages[DashboardViewMode.TABLES] = page
            val scrollToTop = takeScrollToTop(page)
            _uiState.update {
                it.copy(
                    scrollToTopRequest = it.scrollToTopRequest + if (scrollToTop) 1 else 0,
                    tableCards = if (page == 0) newCards else it.tableCards.appendPage(newCards) { card -> card.tableId },
                    hasMoreTableCards = pagination.hasNext,
                    isLoadingDetail = false,
                    isLoadingMore = false,
                    expandedTableSessions = if (page == 0) emptyMap() else it.expandedTableSessions,
                    loadingTableSessions = if (page == 0) emptySet() else it.loadingTableSessions,
                )
            }
        }.onError { uiError ->
            if (!isCurrentLoad(generation, timeRange)) return@onError
            if (page == 0) scrollToTopPending = false
            _uiState.update { it.copy(isLoadingDetail = false, isLoadingMore = false, error = uiError) }
        }
    }

    fun loadTableSessions(tableId: Long) {
        val state = _uiState.value
        if (state.loadingTableSessions.contains(tableId)) return

        _uiState.update { it.copy(loadingTableSessions = it.loadingTableSessions + tableId) }

        viewModelScope.launch {
            val timeRange = state.timeRange ?: return@launch
            val filterEmployeeId =
                if (_viewMode.value == ReportsViewMode.MANAGER_PLUS) {
                    state.selectedEmployeeId
                } else {
                    null
                }
            val paymentTypes = state.selectedPaymentTypes.takeIf { it.isNotEmpty() }?.toList()

            runCatchingApi {
                statisticsApiClient.getTableSessions(
                    tableId = tableId,
                    startDate = timeRange.first,
                    endDate = timeRange.second,
                    employeeId = filterEmployeeId,
                    trainingOnly = state.trainingMode,
                    paymentTypes = paymentTypes,
                )
            }.onSuccess { data ->
                _uiState.update {
                    it.copy(
                        expandedTableSessions = it.expandedTableSessions + (tableId to data.sessions),
                        loadingTableSessions = it.loadingTableSessions - tableId,
                    )
                }
            }.onError { uiError ->
                _uiState.update {
                    it.copy(
                        loadingTableSessions = it.loadingTableSessions - tableId,
                        error = uiError,
                    )
                }
            }
        }
    }

    fun collapseTable(tableId: Long) {
        _uiState.update {
            it.copy(expandedTableSessions = it.expandedTableSessions - tableId)
        }
    }

    private suspend fun loadEmployeeGroups(
        timeRange: Pair<String, String>,
        filterEmployeeId: String?,
        page: Int,
        generation: Int,
    ) {
        val paymentTypes =
            _uiState.value.selectedPaymentTypes
                .takeIf { it.isNotEmpty() }
                ?.toList()
        runCatchingApi {
            statisticsApiClient.getPaginatedEmployeeGroups(
                startDate = timeRange.first,
                endDate = timeRange.second,
                employeeId = filterEmployeeId,
                page = page,
                size = PAGE_SIZE,
                sort = _uiState.value.sortOption,
                trainingOnly = _uiState.value.trainingMode,
                paymentTypes = paymentTypes,
            )
        }.onSuccess { data ->
            if (!isCurrentLoad(generation, timeRange)) return@onSuccess
            val newGroups = data.employeeGroups
            val pagination = data.pagination
            currentPages[DashboardViewMode.EMPLOYEES] = page
            val scrollToTop = takeScrollToTop(page)
            _uiState.update {
                it.copy(
                    scrollToTopRequest = it.scrollToTopRequest + if (scrollToTop) 1 else 0,
                    employeeGroups = if (page == 0) newGroups else it.employeeGroups + newGroups,
                    hasMoreEmployeeGroups = pagination.hasNext,
                    isLoadingDetail = false,
                    isLoadingMore = false,
                )
            }
        }.onError { uiError ->
            if (!isCurrentLoad(generation, timeRange)) return@onError
            if (page == 0) scrollToTopPending = false
            _uiState.update { it.copy(isLoadingDetail = false, isLoadingMore = false, error = uiError) }
        }
    }

    /**
     * Load the next page of data for the current view mode (infinite scroll).
     */
    fun loadMore() {
        val state = _uiState.value
        // A reload in flight decides the first page and whether there are more; paging on now
        // would read the range that reload is about to replace.
        if (state.isLoadingMore || state.isLoading || state.isLoadingDetail) return

        val mode = _dashboardViewMode.value
        val hasMore =
            when (mode) {
                DashboardViewMode.BILLS -> state.hasMoreBills
                DashboardViewMode.TABLES -> state.hasMoreTableCards
                DashboardViewMode.EMPLOYEES -> state.hasMoreEmployeeGroups
            }
        if (!hasMore) return

        // Advance the page counter only after a page loads successfully (see the loaders'
        // onSuccess). If we incremented here, a failed page load would leave the counter
        // ahead of the data and the next loadMore would permanently skip that page of rows.
        val nextPage = (currentPages[mode] ?: 0) + 1
        // Synchronously mark in-flight so a second scroll trigger can't dispatch the same page
        // twice (which would append duplicates) before loadDetailData's coroutine runs.
        _uiState.update { it.copy(isLoadingMore = true) }
        loadDetailData(mode, nextPage)
    }

    /**
     * Handle date preset selection. Reloads statistics for the selected preset range.
     *
     * @param preset The selected date filter preset
     */
    fun onDatePresetSelected(preset: DateFilterPreset) = selectDatePreset(preset, serverStamp = null)

    @OptIn(ExperimentalTime::class)
    private fun selectDatePreset(
        preset: DateFilterPreset,
        serverStamp: (suspend () -> LocalDateTime?)?,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedDatePreset = preset) }

            val now = tillNow()
            val openEnd = openRangeEnd(now, serverStamp)
            val (start, end) =
                when (preset) {
                    DateFilterPreset.SINCE_LAST_ZBON -> getZBonStartTime(zBonApiClient) to openEnd
                    DateFilterPreset.TODAY -> {
                        val startOfDay = LocalDateTime(now.date, LocalTime(0, 0))
                        startOfDay.toString() to openEnd
                    }
                    DateFilterPreset.YESTERDAY -> {
                        val yesterday = now.date.minus(1, DateTimeUnit.DAY)
                        LocalDateTime(yesterday, LocalTime(0, 0)).toString() to
                            LocalDateTime(yesterday, LocalTime(23, 59, 59)).toString()
                    }
                    DateFilterPreset.THIS_WEEK -> {
                        val startOfWeek = now.date.minus(now.dayOfWeek.ordinal.toLong(), DateTimeUnit.DAY)
                        LocalDateTime(startOfWeek, LocalTime(0, 0)).toString() to openEnd
                    }
                    DateFilterPreset.THIS_MONTH -> {
                        val startOfMonth = LocalDate(now.year, now.month.number, 1)
                        LocalDateTime(startOfMonth, LocalTime(0, 0)).toString() to openEnd
                    }
                    DateFilterPreset.CUSTOM -> return@launch
                }

            _uiState.update { it.copy(timeRange = start to end) }
            resetAndReload()
        }
    }

    /**
     * Handle custom date range selection.
     *
     * @param start Start date/time in ISO format
     * @param end End date/time in ISO format
     */
    fun onCustomDateRangeSelected(
        start: String,
        end: String,
    ) {
        _uiState.update {
            it.copy(
                selectedDatePreset = DateFilterPreset.CUSTOM,
                timeRange = start to end,
                showCustomDateRangeDialog = false,
            )
        }
        resetAndReload()
    }

    /**
     * Load every employee's tips for the Trinkgeld sheet's "top tip earners" block.
     *
     * The block used to read [ReportsUiState.employeeGroups], which only the Mitarbeiter tab loads
     * (one page of 20, ordered by amount rather than by tips), so on the default tab the block never
     * appeared. This asks for the groups itself, over the stored range, following every page (the
     * server caps a page at 100). Shop-wide only — a cashier's figures are their own. No-ops once
     * loaded for the current range or while a load is running.
     */
    fun loadTipEarners() {
        val state = _uiState.value
        if (_viewMode.value != ReportsViewMode.MANAGER_PLUS) return
        if (state.tipEarnersLoaded || state.isLoadingTipEarners) return
        val range = state.timeRange ?: return
        // The full request: a result is only valid for the filters it was fetched under.
        val requestKey = tipEarnersRequestKey(state)
        _uiState.update { it.copy(isLoadingTipEarners = true) }
        viewModelScope.launch {
            val groups = mutableListOf<StatisticsDto.EmployeeGroupDto>()
            var page = 0
            while (page < MAX_TIP_EARNER_PAGES) {
                val result =
                    runCatchingApi {
                        statisticsApiClient.getPaginatedEmployeeGroups(
                            startDate = range.first,
                            endDate = range.second,
                            employeeId = state.selectedEmployeeId,
                            page = page,
                            size = TIP_EARNER_PAGE_SIZE,
                            trainingOnly = state.trainingMode,
                        )
                    }
                var hasNext = false
                var failed = false
                result
                    .onSuccess { data ->
                        groups += data.employeeGroups
                        hasNext = data.pagination.hasNext
                    }.onError {
                        failed = true
                    }
                if (failed) {
                    _uiState.update { if (tipEarnersRequestKey(it) == requestKey) it.copy(isLoadingTipEarners = false) else it }
                    return@launch
                }
                if (!hasNext) break
                page++
            }
            _uiState.update {
                // The range, employee filter or training mode moved while the pages were in flight:
                // the reload reset this state already, and these groups belong to the old request.
                if (tipEarnersRequestKey(it) != requestKey) {
                    it
                } else {
                    it.copy(tipEarners = groups, tipEarnersLoaded = true, isLoadingTipEarners = false)
                }
            }
        }
    }

    private fun tipEarnersRequestKey(state: ReportsUiState) = Triple(state.timeRange, state.selectedEmployeeId, state.trainingMode)

    /**
     * Reset accumulated data and page counters, then reload statistics — over the stored range, or
     * over a freshly computed "since the last Z-Bon" range when [recomputeRange] is set.
     */
    private fun resetAndReload(
        recomputeRange: Boolean = false,
        serverStamp: (suspend () -> LocalDateTime?)? = null,
    ) {
        currentPages.clear()
        _uiState.update {
            it.copy(
                onlyBills = emptyList(),
                tableCards = emptyList(),
                employeeGroups = emptyList(),
                tipEarners = emptyList(),
                tipEarnersLoaded = false,
                isLoadingTipEarners = false,
                hasMoreBills = true,
                hasMoreTableCards = true,
                hasMoreEmployeeGroups = true,
                // A page still in flight belongs to the replaced list and is dropped on arrival.
                isLoadingMore = false,
                expandedTableSessions = emptyMap(),
                loadingTableSessions = emptySet(),
                canceledItems = emptyList(),
                paymentRecords = emptyList(),
            )
        }
        val timeRange = _uiState.value.timeRange.takeUnless { recomputeRange }
        if (timeRange != null) {
            loadStatistics(timeRange.first, timeRange.second)
        } else {
            loadStatistics(startTime = null, endTime = null, serverStamp = serverStamp)
        }
    }

    /**
     * Load canceled items on demand (for bottom sheet display).
     * No-ops if items are already loaded or a load is in progress.
     */
    fun loadCanceledItems() {
        val state = _uiState.value
        if (state.canceledItems.isNotEmpty() || state.isLoadingCanceledItems) return

        logger.i { "ReportsViewModel.loadCanceledItems: loading canceled items" }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCanceledItems = true) }
            val timeRange = state.timeRange ?: return@launch
            val (start, end) = timeRange

            runCatchingApi {
                statisticsApiClient.getCanceledItems(
                    startDate = start,
                    endDate = end,
                    employeeId = state.selectedEmployeeId,
                    trainingOnly = state.trainingMode,
                )
            }.onSuccess { data ->
                _uiState.update {
                    it.copy(
                        canceledItems = data.canceledItems,
                        isLoadingCanceledItems = false,
                    )
                }
            }.onError {
                _uiState.update { it.copy(isLoadingCanceledItems = false) }
            }
        }
    }

    /**
     * Load payment records on demand (for payments bottom sheet).
     */
    fun loadPaymentRecords() {
        val state = _uiState.value
        if (state.isLoadingPayments) return

        logger.i { "ReportsViewModel.loadPaymentRecords: loading payment records" }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPayments = true, showPaymentsBottomSheet = true) }
            val timeRange = state.timeRange ?: return@launch
            val filterEmployeeId =
                if (_viewMode.value == ReportsViewMode.MANAGER_PLUS) {
                    state.selectedEmployeeId
                } else {
                    null
                }

            runCatchingApi {
                statisticsApiClient.getPaymentRecords(
                    startDate = timeRange.first,
                    endDate = timeRange.second,
                    employeeId = filterEmployeeId,
                    sort = state.sortOption,
                    trainingOnly = state.trainingMode,
                )
            }.onSuccess { data ->
                _uiState.update {
                    it.copy(
                        paymentRecords = data.payments,
                        isLoadingPayments = false,
                    )
                }
            }.onError {
                _uiState.update { it.copy(isLoadingPayments = false) }
            }
        }
    }

    fun dismissPaymentsBottomSheet() {
        _uiState.update { it.copy(showPaymentsBottomSheet = false, paymentRecords = emptyList()) }
    }

    /**
     * Change sort option and reload current detail data.
     */
    fun onSortChanged(sort: StatisticsDto.StatisticsSortOption) {
        _uiState.update { it.copy(sortOption = sort) }
        // Reset current view data and reload with new sort; a page of the old order is dropped.
        listGeneration++
        val mode = _dashboardViewMode.value
        currentPages[mode] = 0
        _uiState.update {
            when (mode) {
                DashboardViewMode.BILLS -> it.copy(onlyBills = emptyList(), hasMoreBills = true)
                DashboardViewMode.TABLES ->
                    it.copy(
                        tableCards = emptyList(),
                        hasMoreTableCards = true,
                        expandedTableSessions = emptyMap(),
                        loadingTableSessions = emptySet(),
                    )
                DashboardViewMode.EMPLOYEES -> it.copy(employeeGroups = emptyList(), hasMoreEmployeeGroups = true)
            }
        }
        loadDetailData(mode, 0)
    }

    /**
     * Refresh statistics data.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            resetAndReload()
            _isRefreshing.value = false
        }
    }

    /**
     * Clear error state and retry loading.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
        loadStatistics()
    }

    // UI dialog/panel state management

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    /**
     * A Z-Bon has just closed the day: reload the figures, and move to the history tab — where the
     * Z-Bon view model has already opened the new closing — only when [opensHistory]. An operator
     * who may not read the history stays on the daily report and sees the closing in a dialog; the
     * tab the policy withholds is never selected.
     */
    fun onZBonCreated(opensHistory: Boolean) {
        if (opensHistory) selectTab(ZBON_HISTORY_TAB)
        loadStatistics()
    }

    /**
     * Show a past Z-Bon's period on the daily report — the history list's long-press. The one
     * caller that is meant to move the operator, so it selects the report tab itself.
     */
    fun showZBonPeriod(
        startTime: String,
        endTime: String,
    ) {
        selectTab(0)
        loadStatistics(startTime, endTime)
    }

    /**
     * Open the receipt behind a transaction row.
     *
     * The list row is a [BillDto.BillSummaryDto]; the receipt panel needs the full bill, so this
     * fetches it. `isLoadingBill` is raised for the duration and lowered on BOTH legs — a flag left
     * standing would keep a non-dismissible loading overlay on screen for good.
     */
    fun onBillSummaryClick(summary: BillDto.BillSummaryDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBill = true) }
            runCatchingApi { billApiClient.getBillById(summary.billId) }
                .onSuccess { bill ->
                    logger.d { "ReportsViewModel.onBillSummaryClick: bill ${summary.billId} loaded" }
                    _uiState.update {
                        it.copy(selectedBill = bill, billTrail = BillDetailTrail(), showBillPanel = true, isLoadingBill = false)
                    }
                }.onError { uiError ->
                    logger.e { "ReportsViewModel.onBillSummaryClick: bill ${summary.billId} failed with error=$uiError" }
                    _uiState.update { it.copy(error = uiError, isLoadingBill = false) }
                }
        }
    }

    fun onBillFound(bill: BillDto.BillResponseDto) {
        _uiState.update {
            it.copy(selectedBill = bill, billTrail = BillDetailTrail(), showBillPanel = true, showBillSearchSheet = false)
        }
    }

    /**
     * Follow a related-bill link in the open panel ("Storno zu RE-…", "Neubuchung zu RE-…",
     * "Neu gebucht als RE-…", "Storniert durch RE-…"): fetch the linked bill by id and show it
     * in the same panel, remembering the current one so the operator can go back.
     *
     * Same loading/error contract as [onBillSummaryClick]; a failed fetch leaves the current bill open.
     */
    fun openLinkedBill(link: BillDto.BillLinkDto) {
        val current = _uiState.value.selectedBill ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBill = true) }
            runCatchingApi { billApiClient.getBillById(link.billId) }
                .onSuccess { bill ->
                    _uiState.update {
                        it.copy(
                            selectedBill = bill,
                            billTrail = it.billTrail.follow(current, bill.billId),
                            showBillPanel = true,
                            isLoadingBill = false,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "ReportsViewModel.openLinkedBill: bill ${link.billId} failed with error=$uiError" }
                    _uiState.update { it.copy(error = uiError, isLoadingBill = false) }
                }
        }
    }

    /**
     * A Zahlartwechsel booked [newBill]: show it in the open panel with the original on the back
     * trail, and reload the list (the original now reads as stornoed, and two new rows appear).
     *
     * The original on the trail is the copy from before the rebook, so it is re-read too — going
     * back must show it cancelled, linked to its Storno and Neubuchung, and no longer rebookable.
     */
    fun onBillRebooked(newBill: BillDto.BillResponseDto) {
        val original = _uiState.value.selectedBill
        _uiState.update {
            it.copy(
                selectedBill = newBill,
                billTrail = original?.let { shown -> it.billTrail.follow(shown, newBill.billId) } ?: it.billTrail,
                showBillPanel = true,
            )
        }
        (newBill.relations.rebooksBill?.billId ?: original?.billId)?.let(::refreshBillOnTrail)
        // The Neubuchung is the newest Beleg of the change; its stamp comes from the server's clock.
        reloadAfterBillChange { newBill.endTime.toLocalDateTimeOrNull() }
    }

    /**
     * A Zahlartwechsel of [billId] was refused or failed. Re-read the bill: it may have been stornoed
     * or rebooked elsewhere, or the rebook committed before its TSE signing failed — either way the
     * copy on screen must stop offering the rebook. The list is reloaded for the committed case.
     */
    fun onRebookFailed(billId: Long) = onBillChanged(billId)

    /**
     * A Storno of [billId] from the receipt panel booked its Storno Beleg (with or without re-opening
     * the table). The panel closes; the list and the cards must show the new Beleg and the original
     * as stornoed. The Storno Beleg's server stamp is read through the original's `reversedBy` link.
     */
    fun onBillVoided(billId: Long) {
        reloadAfterBillChange { newestServerStamp(readBill(billId)) }
    }

    /**
     * [billId] changed, or turned out to have changed, under the panel: a Storno was refused (e.g.
     * `BILL_ALREADY_DELETED` — stornoed or rebooked on another device), an invoice was issued for it,
     * or its receivable was settled at the till (which books a clearing Beleg). Re-read the bill on
     * screen so its actions match it, and reload the list.
     */
    fun onBillChanged(billId: Long) {
        // One read serves both: the trail refresh (always) and the range end (open presets only).
        val reread = viewModelScope.async { readBill(billId)?.also(::showRefreshed) }
        reloadAfterBillChange { newestServerStamp(reread.await()) }
    }

    /**
     * Reload the list and the cards after a bill mutation from the receipt panel — a Zahlartwechsel
     * (booked or refused) or a Storno (booked or refused). The new Belege are written NOW, after
     * the screen fixed its range, so a plain [refresh] (which reuses the stored range) would miss
     * them. A range that runs "up to now" is therefore recomputed: the default "since the last
     * Z-Bon" exactly as re-entering Berichte does ([loadStatistics] with no range), the other
     * open-ended presets exactly as selecting them does. A closed range the operator chose
     * (yesterday, a custom period) is kept — the new bills belong to today, not to that range.
     *
     * The recomputed range does NOT end at the till's "now" (emu14 REG-1): the server stamps the new
     * Beleg with ITS clock, and a till whose clock lagged it by ~0.4 s asked for a range that ended
     * just before the Storno Beleg — the cards (which drop the cancelled original) moved, the list
     * never showed the Beleg. It ends at the later of the end of the till's today and [serverStamp],
     * the server's own stamp of the newest Beleg of the change, which also holds when the lag
     * straddles midnight (see [openRangeEnd]).
     *
     * Every caller runs only after the server call returned successfully (or was refused), so the
     * write is committed before this reads: the overlay reaches [onBillVoided] from
     * `BillReceiptViewModel.deleteBill`'s `onSuccess`, [onBillRebooked] from `rebookBill`'s
     * `onSuccess`, and [onBillChanged] from a refusal's `onError` or an issued invoice / settlement's
     * `onSuccess` — each behind `apiCall`, which returns only on a 2xx response.
     */
    private fun reloadAfterBillChange(serverStamp: suspend () -> LocalDateTime?) {
        // The new Belege are prepended; once the first page lands, the list scrolls up to show them.
        scrollToTopPending = true
        // The range may first wait for the server stamp: hold the scroll trigger off the old range
        // meanwhile, and drop any page still in flight over it.
        listGeneration++
        _uiState.update { it.copy(isLoading = true) }
        reloadForSelectedPreset(serverStamp)
    }

    /**
     * Reload the list and the cards for the selected preset: an open range ("up to now") is
     * recomputed — "since the last Z-Bon" exactly as [loadStatistics] with no range, the other open
     * presets exactly as selecting them — and a closed range the operator chose is kept.
     */
    private fun reloadForSelectedPreset(serverStamp: (suspend () -> LocalDateTime?)?) {
        when (val preset = _uiState.value.selectedDatePreset) {
            DateFilterPreset.SINCE_LAST_ZBON -> resetAndReload(recomputeRange = true, serverStamp = serverStamp)
            DateFilterPreset.TODAY, DateFilterPreset.THIS_WEEK, DateFilterPreset.THIS_MONTH ->
                selectDatePreset(preset, serverStamp = serverStamp)
            DateFilterPreset.YESTERDAY, DateFilterPreset.CUSTOM -> resetAndReload()
        }
    }

    /**
     * Another device (or the API) may have written a bill — the till was told so by the server's
     * push (a table's status changed: a table billed, part-paid or reopened). The list fixed its
     * range when the screen opened, so without this such a bill only appeared after leaving and
     * re-entering Berichte (emu17 NOTE-c).
     *
     * Pushes are coalesced for [EXTERNAL_CHANGE_DEBOUNCE_MS]; then the bill count over the open
     * range extended to the end of today is compared with the count on screen, and only a
     * difference reloads — an order placed on another table pushes the same event and must not
     * reset the list the operator is reading. A closed range (yesterday, a custom period) is left
     * alone: the new bill belongs to today.
     */
    fun onBillsMayHaveChangedElsewhere() {
        if (_uiState.value.selectedDatePreset !in OPEN_PRESETS) return
        externalChangeCheck?.cancel()
        externalChangeCheck =
            viewModelScope.launch {
                delay(EXTERNAL_CHANGE_DEBOUNCE_MS)
                if (hasBillsBeyondList()) reloadAfterBillChange(serverStamp = { null })
            }
    }

    /** Whether the server counts more (or fewer) bills up to the end of today than the screen shows. */
    private suspend fun hasBillsBeyondList(): Boolean {
        val state = _uiState.value
        if (state.isLoading) return false // a load is already on its way and reads the server anew
        val start = state.timeRange?.first ?: return false
        val endOfToday = LocalDateTime(tillNow().date, END_OF_DAY).toString()
        val filterEmployeeId = state.selectedEmployeeId.takeIf { _viewMode.value == ReportsViewMode.MANAGER_PLUS }
        return runCatchingApi {
            statisticsApiClient.getSalesOverview(
                startDate = start,
                endDate = endOfToday,
                employeeId = filterEmployeeId,
                trainingOnly = state.trainingMode,
            )
        }.onError { uiError -> logger.w { "ReportsViewModel.hasBillsBeyondList: check failed: $uiError" } }
            .getOrNull()
            ?.let { it.totalBillCount != _uiState.value.totalBillCount } ?: false
    }

    /** Re-read [billId] from the server; null (logged) when it cannot be read. */
    private suspend fun readBill(billId: Long): BillDto.BillResponseDto? =
        runCatchingApi { billApiClient.getBillById(billId) }
            .onError { uiError -> logger.w { "ReportsViewModel.readBill: bill $billId not re-read: $uiError" } }
            .getOrNull()

    /**
     * The server's stamp of the newest Beleg a change of [bill] produced: the bill itself and the
     * Storno / Neubuchung it now links to (`reversedBy`, `rebookedBy`). Null when none can be read —
     * the range then ends at the end of the till's today.
     */
    private suspend fun newestServerStamp(bill: BillDto.BillResponseDto?): LocalDateTime? {
        if (bill == null) return null
        val linked = listOfNotNull(bill.relations.reversedBy, bill.relations.rebookedBy).mapNotNull { readBill(it.billId) }
        return (listOf(bill) + linked).mapNotNull { it.endTime.toLocalDateTimeOrNull() }.maxOrNull()
    }

    private fun refreshBillOnTrail(billId: Long) {
        viewModelScope.launch { readBill(billId)?.let(::showRefreshed) }
    }

    /** Put a re-read [bill] on the trail — and on screen, unless back was pressed meanwhile. */
    private fun showRefreshed(bill: BillDto.BillResponseDto) {
        _uiState.update {
            it.copy(
                selectedBill = if (it.selectedBill?.billId == bill.billId) bill else it.selectedBill,
                billTrail = it.billTrail.refreshed(bill),
            )
        }
    }

    /** Return to the bill the last followed link was opened from; a no-op when there is none. */
    fun backToPreviousBill() {
        val (previous, trail) = _uiState.value.billTrail.back()
        if (previous == null) return
        _uiState.update { it.copy(selectedBill = previous, billTrail = trail) }
    }

    fun dismissBillPanel() {
        _uiState.update { it.copy(showBillPanel = false, selectedBill = null, billTrail = BillDetailTrail()) }
    }

    fun showZBonConfirmDialog() {
        _uiState.update { it.copy(showZBonConfirmDialog = true) }
    }

    fun dismissZBonConfirmDialog() {
        _uiState.update { it.copy(showZBonConfirmDialog = false) }
    }

    fun showBillSearchSheet() {
        _uiState.update { it.copy(showBillSearchSheet = true) }
    }

    fun dismissBillSearchSheet() {
        _uiState.update { it.copy(showBillSearchSheet = false) }
    }

    fun showCustomDateRangeDialog() {
        _uiState.update { it.copy(showCustomDateRangeDialog = true) }
    }

    fun dismissCustomDateRangeDialog() {
        _uiState.update { it.copy(showCustomDateRangeDialog = false) }
    }

    /** The till's "now", from its own (injectable) clock. */
    private fun tillNow(): LocalDateTime = clock.now().toLocalDateTime(timeZone)

    /**
     * The end of an open range ("up to now"): the end of the till's today, or — after a bill change —
     * the server's stamp of the newest Beleg when that is later. Nothing is booked in the future, so
     * the range still means "up to now".
     *
     * It is never the till's own "now". The server stamps every Beleg with ITS clock, and a till whose
     * clock lags it ends its range before the newest Belege: by a fraction of a second (emu14 REG-1,
     * after a Storno) or by whole seconds to minutes (emu19 BIL-07 — an emulator restored from a
     * snapshot): a bill booked elsewhere while the till sat on the Dashboard was missing from Berichte
     * on entry, and pull-to-refresh (which keeps the stored range) never brought it in. At 23:59:59
     * on the till against 00:00:01 on the server the lag crosses midnight, which only [serverStamp]
     * covers.
     */
    private suspend fun openRangeEnd(
        now: LocalDateTime,
        serverStamp: (suspend () -> LocalDateTime?)?,
    ): String {
        val endOfToday = LocalDateTime(now.date, END_OF_DAY)
        val stamp = serverStamp?.invoke() ?: return endOfToday.toString()
        return maxOf(endOfToday, stamp).toString()
    }

    private fun String.toLocalDateTimeOrNull(): LocalDateTime? = runCatching { LocalDateTime.parse(this) }.getOrNull()
}
