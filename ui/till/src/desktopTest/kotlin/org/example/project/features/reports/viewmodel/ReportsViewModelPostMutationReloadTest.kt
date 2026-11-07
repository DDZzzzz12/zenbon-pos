package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A client (or the rest of one) this test does not exercise: every call fails like a transport error. */
private inline fun <reified T : Any> unexercised(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name}"))
    } as T

/**
 * After a bill mutation from the Berichte receipt panel — a Zahlartwechsel that succeeded or was
 * refused, an ordinary Storno — the LIST must show the bills that mutation (or a concurrent one on
 * another device) wrote after the screen fixed its range. Pull-to-refresh keeps the stored range.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ReportsViewModelPostMutationReloadTest {
    private val testDispatcher = StandardTestDispatcher()

    /** The server's bills, by id; each carries the moment it was written. */
    private val serverBills = linkedMapOf<Long, BillDto.BillSummaryDto>()

    /** Every `getPaginatedBills` call as (endDate, page). */
    private val listCalls = mutableListOf<Pair<String, Int>>()

    private fun now(): String =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .toString()

    private fun write(id: Long) {
        serverBills[id] =
            BillDto.BillSummaryDto(
                billId = id,
                billNumber = "RE-$id",
                billAmount = "10.00",
                paidAmount = "10.00",
                tips = "0.00",
                endTime = now(),
                employeeNumber = "07",
            )
    }

    private fun inRange(
        bill: BillDto.BillSummaryDto,
        start: String,
        end: String,
    ): Boolean {
        val at = LocalDateTime.parse(bill.endTime)
        return at >= LocalDateTime.parse(start) && at <= LocalDateTime.parse(end)
    }

    private fun listed(
        start: String,
        end: String,
    ) = serverBills.values.filter { inRange(it, start, end) }.sortedByDescending { it.billId }

    /** How long a list page over a given range end takes to answer, in virtual ms (default: at once). */
    private val pageLatency = mutableMapOf<String, Long>()

    /** Serves [serverBills] filtered by the requested range, like the server's attributed-time clause. */
    private val statisticsApi: StatisticsApiClient =
        object : StatisticsApiClient by unexercised<StatisticsApiClient>() {
            override suspend fun getSalesOverview(
                startDate: String,
                endDate: String,
                employeeId: String?,
                trainingOnly: Boolean,
                compare: String?,
                granularity: String?,
            ) = StatisticsDto.SalesOverviewResponseDto(
                period = "$startDate/$endDate",
                totalRevenue = "0.00",
                billedRevenue = "0.00",
                canceledRevenue = "0.00",
                openItemAmount = "0.00",
                totalTips = "0.00",
                totalBillCount = listed(startDate, endDate).size,
            )

            override suspend fun getPaginatedBills(
                startDate: String,
                endDate: String,
                employeeId: String?,
                page: Int?,
                size: Int?,
                sort: StatisticsDto.StatisticsSortOption?,
                trainingOnly: Boolean,
                paymentTypes: List<String>?,
            ): StatisticsDto.PaginatedBillsResponseDto {
                val p = page ?: 0
                val n = size ?: ReportsViewModel.PAGE_SIZE
                listCalls += endDate to p
                val all = listed(startDate, endDate) // read on arrival at the server, as the real one does
                pageLatency[endDate]?.let { delay(it) }
                return StatisticsDto.PaginatedBillsResponseDto(
                    bills = all.drop(p * n).take(n),
                    totalAmount = "0.00",
                    totalTips = "0.00",
                    pagination =
                        ApiResponseDto.PaginationInfo(
                            currentPage = p,
                            pageSize = n,
                            totalItems = all.size.toLong(),
                            totalPages = (all.size + n - 1) / n,
                            hasNext = (p + 1) * n < all.size,
                            hasPrevious = p > 0,
                        ),
                )
            }
        }

    /** A shop with no Z-Bon yet: "since the last Z-Bon" runs from today's midnight to now. */
    private val noZBonYet: ZBonApiClient =
        Proxy.newProxyInstance(ZBonApiClient::class.java.classLoader, arrayOf(ZBonApiClient::class.java)) { _, method, _ ->
            if (method.name != "getLatestZBonEndTime") throw NetworkApiException(UnsupportedOperationException(method.name))
            null
        } as ZBonApiClient

    private val billApi: BillApiClient =
        Proxy.newProxyInstance(BillApiClient::class.java.classLoader, arrayOf(BillApiClient::class.java)) { _, method, args ->
            if (method.name != "getBillById") throw NetworkApiException(UnsupportedOperationException(method.name))
            val id = args[0] as Long
            serverDetails[id] ?: detail(id)
        } as BillApiClient

    /** Full bills the server serves by id, where a test needs more than [detail]'s placeholder. */
    private val serverDetails = mutableMapOf<Long, BillDto.BillResponseDto>()

    /**
     * A bill the SERVER wrote at [endTime] by its own clock — on the list, and readable by id. A
     * Storno Beleg [reverses] its original, which then links to it as `reversedBy`, as on the server.
     */
    private fun writeAt(
        id: Long,
        endTime: String,
        reverses: Long? = null,
    ) {
        serverBills[id] =
            BillDto.BillSummaryDto(
                billId = id,
                billNumber = "RE-$id",
                billAmount = "10.00",
                paidAmount = "10.00",
                tips = "0.00",
                endTime = endTime,
                employeeNumber = "07",
            )
        serverDetails[id] = detail(id).copy(endTime = endTime)
        reverses?.let { original ->
            serverDetails[original] =
                (serverDetails[original] ?: detail(original)).copy(
                    relations = BillDto.BillRelationsDto(isCancelled = true, reversedBy = BillDto.BillLinkDto(id, "RE-$id")),
                )
        }
    }

    /** A till clock standing still at [at] (the till's local time). */
    private fun tillClockAt(at: String): Clock =
        object : Clock {
            override fun now(): Instant = LocalDateTime.parse(at).toInstant(TimeZone.currentSystemDefault())
        }

    /** The last Z-Bon closed on the evening before the till's day, so "since the last Z-Bon" is fixed. */
    private val zBonClosedOn29th: ZBonApiClient =
        Proxy.newProxyInstance(ZBonApiClient::class.java.classLoader, arrayOf(ZBonApiClient::class.java)) { _, method, _ ->
            if (method.name != "getLatestZBonEndTime") throw NetworkApiException(UnsupportedOperationException(method.name))
            "2026-09-29T22:00:00"
        } as ZBonApiClient

    private fun detail(id: Long) =
        BillDto.BillResponseDto(
            billId = id,
            employeeId = "emp-1",
            employeeNumber = "07",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 500L,
            tableNumber = "12",
            billNumber = "RE-$id",
            billAmount = "10.00",
            paidAmount = "10.00",
            tips = "0.00",
            startTime = "2026-09-27T12:00:00",
            endTime = "2026-09-27T12:00:00",
            orderItems = emptyList(),
            payments = emptyList(),
        )

    private fun viewModel(
        tillClock: Clock = Clock.System,
        zBonApiClient: ZBonApiClient = noZBonYet,
    ) = ReportsViewModel(
        statisticsApiClient = statisticsApi,
        zBonApiClient = zBonApiClient,
        employeeApiClient = unexercised<EmployeeApiClient>(),
        billApiClient = billApi,
        paymentTypeApiClient = unexercised<PaymentTypeApiClient>(),
        clock = tillClock,
    )

    private fun ReportsViewModel.listedIds() = uiState.value.onlyBills.map { it.billId }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Opens Berichte on bill 1, then lets the clock move on so later writes fall after the range end. */
    private fun kotlinx.coroutines.test.TestScope.openScreenOn(vm: ReportsViewModel) {
        write(1)
        vm.loadStatistics()
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.listedIds())
        vm.onBillFound(detail(1))
        Thread.sleep(5)
    }

    @Test
    fun `after a refused Zahlartwechsel the list shows the bills the other device wrote`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            write(2) // the other device's Storno
            write(3) // … and its Neubuchung

            vm.onRebookFailed(1L)
            // The emptied list is still "at the bottom" to the scroll trigger, so it asks for more at once.
            vm.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(3L, 2L, 1L), vm.listedIds(), "list calls: $listCalls")
            assertTrue(listCalls.none { (_, page) -> page > 0 }, "a reload is not followed by a page over the old range: $listCalls")
        }

    @Test
    fun `after an ordinary Storno the list shows the Storno Beleg`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            write(2) // the Storno Beleg, booked now

            vm.onBillVoided(1L)
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds())
            assertEquals(2, vm.uiState.value.totalBillCount, "the cards follow the list")
        }

    /**
     * Opens Berichte ("since the last Z-Bon") on a till whose clock stands at [tillNow], with the
     * original bill 1 written at [originalAt], then the server writes Storno Beleg 2 at [stornoAt]
     * by ITS clock and the overlay reports the Storno.
     */
    private fun kotlinx.coroutines.test.TestScope.stornoUnderClockSkew(
        tillNow: String,
        originalAt: String,
        stornoAt: String,
        preset: DateFilterPreset? = null,
    ): ReportsViewModel {
        writeAt(1, originalAt)
        val vm = viewModel(tillClock = tillClockAt(tillNow), zBonApiClient = zBonClosedOn29th)
        if (preset == null) vm.loadStatistics() else vm.onDatePresetSelected(preset)
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.listedIds())

        writeAt(2, stornoAt, reverses = 1)
        vm.onBillVoided(1L)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `after an ordinary Storno the list shows the Storno Beleg even when the till's clock lags the server's`() =
        runTest(testDispatcher) {
            // emu14 REG-1: the server stamps the Storno Beleg with its own clock; the till's lagged it
            // (~0.4 s measured), so the reload asked for a range ending just before the Beleg. The cards
            // moved (the cancelled original drops out of them), the list never showed the new Beleg.
            val vm = stornoUnderClockSkew(tillNow = "2026-09-30T12:00:00", originalAt = "2026-09-30T11:00:00", stornoAt = "2026-09-30T12:00:02")

            assertEquals(listOf(2L, 1L), vm.listedIds(), "list calls: $listCalls")
        }

    @Test
    fun `a Storno the server stamps after midnight is listed although the till is still on the day before`() =
        runTest(testDispatcher) {
            // The lag straddles midnight: the end of the TILL's today (30th, 23:59:59.999999) still
            // ends before a Beleg the server stamped at 00:00:01 on the 1st.
            val vm = stornoUnderClockSkew(tillNow = "2026-09-30T23:59:59", originalAt = "2026-09-30T23:50:00", stornoAt = "2026-10-01T00:00:01")

            assertEquals(listOf(2L, 1L), vm.listedIds(), "list calls: $listCalls")
        }

    @Test
    fun `the same midnight Storno is listed under the today preset`() =
        runTest(testDispatcher) {
            val vm =
                stornoUnderClockSkew(
                    tillNow = "2026-09-30T23:59:59",
                    originalAt = "2026-09-30T23:50:00",
                    stornoAt = "2026-10-01T00:00:01",
                    preset = DateFilterPreset.TODAY,
                )

            assertEquals(listOf(2L, 1L), vm.listedIds(), "list calls: $listCalls")
            assertEquals(DateFilterPreset.TODAY, vm.uiState.value.selectedDatePreset)
        }

    @Test
    fun `a refused Storno re-reads the list too`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            write(2) // stornoed elsewhere meanwhile

            vm.onBillChanged(1L)
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds())
        }

    @Test
    fun `a page still in flight over the old range does not land on the reloaded list`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            (1L..25L).forEach(::write) // more than one page
            vm.loadStatistics()
            advanceUntilIdle()
            val oldEnd = assertNotNull(vm.uiState.value.timeRange).second
            assertEquals((25L downTo 6L).toList(), vm.listedIds())
            vm.onBillFound(detail(25))
            Thread.sleep(5)

            // The operator scrolls: page 2 over the old range is slow to answer …
            pageLatency[oldEnd] = 1_000
            vm.loadMore()
            // … and meanwhile a Zahlartwechsel elsewhere is refused here and the list reloads over "up to now".
            write(26)
            write(27)
            vm.onRebookFailed(25L)
            advanceUntilIdle()

            assertEquals(
                (27L downTo 8L).toList(),
                vm.listedIds(),
                "the reloaded first page, not with the old range's second page appended: $listCalls",
            )
            assertTrue(vm.uiState.value.hasMoreBills, "the reloaded list still has a second page to fetch")
        }

    @Test
    fun `every bill-change reload asks the list to scroll to its top once the first page has landed`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            val before = vm.uiState.value.scrollToTopRequest

            write(2)
            vm.onBillVoided(1L)
            assertEquals(before, vm.uiState.value.scrollToTopRequest, "not before the new page is there")
            advanceUntilIdle()
            assertEquals(before + 1, vm.uiState.value.scrollToTopRequest, "Storno")
            assertEquals(2L, vm.listedIds().first(), "the request lands with the page that carries the new Beleg")

            vm.onRebookFailed(1L)
            advanceUntilIdle()
            assertEquals(before + 2, vm.uiState.value.scrollToTopRequest, "refused Zahlartwechsel")

            vm.onBillRebooked(detail(3))
            advanceUntilIdle()
            assertEquals(before + 3, vm.uiState.value.scrollToTopRequest, "Zahlartwechsel")

            vm.onBillChanged(1L)
            advanceUntilIdle()
            assertEquals(before + 4, vm.uiState.value.scrollToTopRequest, "refused Storno, invoice, settlement")
        }

    @Test
    fun `pull-to-refresh and paging do not scroll the list`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            (1L..25L).forEach(::write)
            vm.loadStatistics()
            advanceUntilIdle()
            val before = vm.uiState.value.scrollToTopRequest

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(25, vm.listedIds().size, "the second page was loaded")
            vm.refresh()
            advanceUntilIdle()

            assertEquals(before, vm.uiState.value.scrollToTopRequest)
        }

    @Test
    fun `pull-to-refresh keeps the stored range`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            val range = vm.uiState.value.timeRange
            write(2)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(range, vm.uiState.value.timeRange)
            // The stored open range runs to the end of today (emu19 BIL-07), so the refresh brings in
            // a bill written since — it used to end at the till's "now" on entry and keep it stale.
            assertEquals(listOf(2L, 1L), vm.listedIds())
        }

    // ---- emu19 BIL-07: entering Berichte must show a bill booked while the till sat on the Dashboard ----

    @Test
    fun `entering Berichte lists a bill the server stamped after the till's lagging now`() =
        runTest(testDispatcher) {
            // The emulator's clock lagged the server's: the bill booked by API 5 s before entry carried a
            // server stamp later than the till's "now", and a range ending at the till's now missed it.
            writeAt(1, "2026-09-30T11:00:00")
            writeAt(2, "2026-09-30T12:01:30")
            val vm = viewModel(tillClock = tillClockAt("2026-09-30T12:00:00"), zBonApiClient = zBonClosedOn29th)

            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds(), "list calls: $listCalls")
            assertEquals(2, vm.uiState.value.totalBillCount, "the cards count it too")
        }

    @Test
    fun `re-entering Berichte on a retained view model reloads the list`() =
        runTest(testDispatcher) {
            // Verwaltung destinations save and restore their state, so the same VM sees the second entry.
            val vm = viewModel(tillClock = tillClockAt("2026-09-30T12:00:00"), zBonApiClient = zBonClosedOn29th)
            writeAt(1, "2026-09-30T11:00:00")
            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()
            assertEquals(listOf(1L), vm.listedIds())

            writeAt(2, "2026-09-30T12:00:05") // booked on another device while the till was on the Dashboard
            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds(), "list calls: $listCalls")
        }

    @Test
    fun `re-entering Berichte keeps the preset the operator left selected`() =
        runTest(testDispatcher) {
            val vm = viewModel(tillClock = tillClockAt("2026-09-30T12:00:00"), zBonApiClient = zBonClosedOn29th)
            writeAt(1, "2026-09-29T23:00:00") // since the last Z-Bon, but not today
            writeAt(2, "2026-09-30T11:00:00")
            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()
            vm.onDatePresetSelected(DateFilterPreset.TODAY)
            advanceUntilIdle()
            assertEquals(listOf(2L), vm.listedIds())

            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()

            assertEquals(DateFilterPreset.TODAY, vm.uiState.value.selectedDatePreset)
            assertEquals(listOf(2L), vm.listedIds(), "the chip says Heute, so the list is today's: $listCalls")
        }

    // ---- emu17 NOTE-c: a bill written on another device, announced by the server's push ----

    @Test
    fun `a bill another device wrote appears after the server's push, without leaving the screen`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            write(2) // RE-15 on the other device: its table closes, the server pushes the table status

            vm.onBillsMayHaveChangedElsewhere()
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds())
            assertEquals(2, vm.uiState.value.totalBillCount, "the cards count it too")
        }

    /**
     * The push arrives whatever tab is showing. An operator reading a Z-Bon in "Z-Bon Verlauf" was
     * thrown back to the daily report — and out of the Z-Bon detail — because a finished load
     * selected tab 0. The reload refreshes the figures behind the tab, it does not move the operator.
     */
    @Test
    fun `a bill push reloads the list without leaving the Z-Bon history tab`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            vm.selectTab(ReportsViewModel.ZBON_HISTORY_TAB)
            write(2)

            vm.onBillsMayHaveChangedElsewhere()
            advanceUntilIdle()

            assertEquals(listOf(2L, 1L), vm.listedIds(), "the reload still ran")
            assertEquals(ReportsViewModel.ZBON_HISTORY_TAB, vm.uiState.value.selectedTabIndex)
        }

    @Test
    fun `showing a Z-Bon's period is the one load that moves to the daily report`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            vm.selectTab(ReportsViewModel.ZBON_HISTORY_TAB)

            vm.showZBonPeriod("2026-03-07T08:00:01", "2026-03-07T22:00:00")
            advanceUntilIdle()

            assertEquals(0, vm.uiState.value.selectedTabIndex)
            assertEquals("2026-03-07T08:00:01" to "2026-03-07T22:00:00", vm.uiState.value.timeRange)
        }

    @Test
    fun `a push that wrote no bill, such as an order on another table, does not reload the list`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            val callsBefore = listCalls.size

            vm.onBillsMayHaveChangedElsewhere()
            advanceUntilIdle()

            assertEquals(callsBefore, listCalls.size, "the list the operator is reading is left alone")
            assertEquals(listOf(1L), vm.listedIds())
        }

    @Test
    fun `a burst of pushes is checked once and reloads the list once`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            val callsBefore = listCalls.size
            write(2)
            write(3)

            repeat(3) { vm.onBillsMayHaveChangedElsewhere() }
            advanceUntilIdle()

            assertEquals(callsBefore + 1, listCalls.size)
            assertEquals(listOf(3L, 2L, 1L), vm.listedIds())
        }

    @Test
    fun `a closed range is not reloaded by a push - the new bill belongs to today`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            openScreenOn(vm)
            vm.onDatePresetSelected(DateFilterPreset.YESTERDAY)
            advanceUntilIdle()
            val callsBefore = listCalls.size
            write(2)

            vm.onBillsMayHaveChangedElsewhere()
            advanceUntilIdle()

            assertEquals(callsBefore, listCalls.size)
        }
}
