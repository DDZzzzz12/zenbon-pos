package org.example.project.admin.features.voucher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.voucher.presentation.VoucherTab
import org.example.project.admin.features.voucher.presentation.VoucherViewModel
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun voucher(id: Long) =
    VoucherDto.VoucherResponseDto(
        id = id,
        sessionId = null,
        voucherNumber = "G2026-000$id",
        employeeNumber = "07",
        orderItemId = null,
        originalValue = "50.00",
        remainingValue = "20.00",
        totalRedeemed = "30.00",
        isFullyRedeemed = false,
        createdAt = "2026-08-03T14:12:00",
        fullyRedeemedAt = null,
        status = "ACTIVE",
    )

private fun stats() =
    VoucherDto.VoucherStatisticsDto(
        totalVouchers = 42,
        activeVouchers = 17,
        fullyRedeemedVouchers = 20,
        totalValueIssued = "1420.00",
        totalValueRedeemed = "560.00",
        totalValueRemaining = "860.00",
        invalidatedVouchers = 5,
        totalValueInvalidated = "100.00",
    )

@OptIn(ExperimentalCoroutinesApi::class)
class VoucherViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: FakeVoucherApiClient
    private lateinit var vm: VoucherViewModel

    private val anchor = LocalDate(2026, 8, 15)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeVoucherApiClient()
        api.statistics = stats()
        vm = VoucherViewModel(api)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun load_fetchesBothDateRangesInParallel() =
        runTest(dispatcher) {
            api.issued = listOf(voucher(1), voucher(2))
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            assertEquals(1, api.issuedCalls)
            assertEquals(1, api.redeemedCalls)
            assertEquals(2, vm.state.issued.size)
            assertNull(vm.state.error)
        }

    @Test
    fun load_doesNotRefetchActiveOrStatistics() =
        runTest(dispatcher) {
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            vm.load(ReportsGranularity.MONTH, LocalDate(2026, 7, 15))
            advanceUntilIdle()
            assertEquals(2, api.issuedCalls)
            assertEquals(0, api.activeCalls)
            assertEquals(1, api.statisticsCalls)
        }

    /**
     * Regression: a slower response from an earlier period must not overwrite a newer one. Two quick
     * period-stepper clicks used to leave two in-flight request pairs, and whichever landed last won
     * the write — so the table and both period KPIs could show one month's data under another
     * month's label, with no error to hint at it.
     */
    @Test
    fun load_staleResponseCannotOverwriteTheNewerOne() =
        runTest(dispatcher) {
            api.rangeDelayMs = 1_000
            api.issued = listOf(voucher(1))
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceTimeBy(100)

            // Second click lands while the first pair is still in flight, carrying different data.
            api.issued = listOf(voucher(2), voucher(3))
            val newerAnchor = LocalDate(2026, 7, 15)
            vm.load(ReportsGranularity.MONTH, newerAnchor)
            advanceUntilIdle()

            assertEquals(newerAnchor, vm.state.anchor)
            assertEquals(2, vm.state.issued.size, "the newer period's data must win")
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun selectTab_doesNotRefetchAnAlreadyLoadedTab() =
        runTest(dispatcher) {
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            vm.selectTab(VoucherTab.REDEEMED)
            advanceUntilIdle()
            assertEquals(1, api.redeemedCalls)
        }

    @Test
    fun selectTab_loadsActiveOnFirstVisitOnly() =
        runTest(dispatcher) {
            api.active = listOf(voucher(1))
            vm.selectTab(VoucherTab.ACTIVE)
            advanceUntilIdle()
            vm.selectTab(VoucherTab.ISSUED)
            advanceUntilIdle()
            vm.selectTab(VoucherTab.ACTIVE)
            advanceUntilIdle()
            assertEquals(1, api.activeCalls)
        }

    @Test
    fun search_swapsTheSourceAndClearingRestoresTheTab() =
        runTest(dispatcher) {
            api.searchResults = listOf(voucher(9))
            vm.onSearchTermChange("G2026-000")
            advanceUntilIdle()
            assertTrue(vm.state.isSearchActive)
            assertFalse(vm.state.filtersApply)
            assertEquals(1, vm.state.searchResults.size)

            vm.clearSearch()
            advanceUntilIdle()
            assertFalse(vm.state.isSearchActive)
            assertTrue(vm.state.filtersApply)
        }

    /**
     * Regression: cancelling an in-flight search must clear the loading flag. `runCatchingApi`
     * rethrows `CancellationException`, so neither `onSuccess` nor `onError` runs on cancel — and
     * `PosLoadingOverlay` is a non-dismissible Dialog, so a stuck flag hard-locks the whole screen
     * until the page is reloaded.
     */
    @Test
    fun search_cancelledMidFlightClearsTheLoadingFlag() =
        runTest(dispatcher) {
            api.searchDelayMs = 1_000
            vm.onSearchTermChange("G2026-000")
            // Past the 300 ms debounce, so the request is running but has not returned.
            advanceTimeBy(400)
            assertTrue(vm.state.isLoading, "precondition: the search should be in flight")

            vm.onSearchTermChange("")
            advanceUntilIdle()
            assertFalse(vm.state.isLoading, "cancelling the search must not leave the overlay up")
        }

    @Test
    fun search_neverSendsABlankTerm() =
        runTest(dispatcher) {
            vm.onSearchTermChange("   ")
            advanceUntilIdle()
            assertEquals(0, api.searchCalls)
        }

    @Test
    fun loadMoreActive_appendsAndStopsOnAShortPage() =
        runTest(dispatcher) {
            api.active = List(50) { voucher(it.toLong()) }
            vm.selectTab(VoucherTab.ACTIVE)
            advanceUntilIdle()
            assertTrue(vm.state.activeHasMore)

            api.active = listOf(voucher(99))
            vm.loadMoreActive()
            advanceUntilIdle()
            assertEquals(51, vm.state.active.size)
            assertEquals(50, api.lastOffset)
            assertFalse(vm.state.activeHasMore)
        }

    @Test
    fun statisticsFailure_leavesKpisNullWithoutSettingError() =
        runTest(dispatcher) {
            api.statisticsFailure = { throw NetworkApiException(RuntimeException("down")) }
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            assertNull(vm.state.statistics)
            assertNull(vm.state.error)
        }

    @Test
    fun failingParallelChildSurfacesAsErrorRatherThanEscaping() =
        runTest(dispatcher) {
            api.issuedFailure = { throw NetworkApiException(RuntimeException("down")) }
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            assertIs<org.example.project.core.presentation.error.UiError.Network>(vm.state.error)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun employeeFilter_isSentAndTriggersAReload() =
        runTest(dispatcher) {
            vm.load(ReportsGranularity.MONTH, anchor)
            advanceUntilIdle()
            vm.selectEmployee("uuid-7")
            advanceUntilIdle()
            assertEquals("uuid-7", api.lastEmployeeId)
            assertEquals(2, api.issuedCalls)
        }
}
