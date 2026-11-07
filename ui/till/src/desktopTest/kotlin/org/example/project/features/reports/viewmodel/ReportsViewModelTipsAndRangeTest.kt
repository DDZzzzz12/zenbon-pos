package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private inline fun <reified T : Any> unserved(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name} is not used here"))
    } as T

private fun overview(
    tips: String,
    bills: Int,
) = StatisticsDto.SalesOverviewResponseDto(
    period = "custom",
    totalRevenue = "100.00",
    billedRevenue = "100.00",
    canceledRevenue = "0.00",
    openItemAmount = "0.00",
    totalTips = tips,
    totalBillCount = bills,
)

private fun group(
    number: String,
    tips: String,
) = StatisticsDto.EmployeeGroupDto(
    employeeNumber = number,
    employeeName = "Emp $number",
    totalRevenue = "50.00",
    totalTips = tips,
    billCount = 3,
    bills = emptyList(),
)

private fun page(
    groups: List<StatisticsDto.EmployeeGroupDto>,
    hasNext: Boolean = false,
    current: Int = 0,
) = StatisticsDto.PaginatedEmployeeGroupsResponseDto(
    employeeGroups = groups,
    pagination =
        ApiResponseDto.PaginationInfo(
            currentPage = current,
            pageSize = 100,
            totalItems = groups.size.toLong(),
            totalPages = if (hasNext) current + 2 else current + 1,
            hasNext = hasNext,
            hasPrevious = current > 0,
        ),
)

/**
 * FO-64 (average tip per bill), FO-70 (tip earners load without the Mitarbeiter tab) and FO-40
 * (the custom date range the Tagesbericht picks) on the shared reports view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTipsAndRangeTest {
    private val testDispatcher = StandardTestDispatcher()
    private val groupPagesRequested = mutableListOf<Int?>()

    /** Runs inside the first tip-earner page request, i.e. while that request is "in flight". */
    private var duringGroupPageRequest: (() -> Unit)? = null

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun statistics(
        overview: StatisticsDto.SalesOverviewResponseDto,
        groupPages: List<StatisticsDto.PaginatedEmployeeGroupsResponseDto> = emptyList(),
    ): StatisticsApiClient =
        Proxy.newProxyInstance(StatisticsApiClient::class.java.classLoader, arrayOf(StatisticsApiClient::class.java)) { _, method, args ->
            when (method.name) {
                "getSalesOverview" -> overview
                "getPaginatedEmployeeGroups" -> {
                    // (startDate, endDate, employeeId, page, size, ...)
                    val pageIndex = args[3] as Int?
                    groupPagesRequested += pageIndex
                    duringGroupPageRequest?.also { duringGroupPageRequest = null }?.invoke()
                    groupPages[pageIndex ?: 0]
                }
                else -> throw NetworkApiException(UnsupportedOperationException(method.name))
            }
        } as StatisticsApiClient

    /** A shop with no Z-Bon yet, so the open range can be computed. */
    private val noZBonYet: ZBonApiClient =
        Proxy.newProxyInstance(ZBonApiClient::class.java.classLoader, arrayOf(ZBonApiClient::class.java)) { _, method, _ ->
            if (method.name != "getLatestZBonEndTime") throw NetworkApiException(UnsupportedOperationException(method.name))
            null
        } as ZBonApiClient

    private fun viewModel(statisticsApiClient: StatisticsApiClient) =
        ReportsViewModel(
            statisticsApiClient = statisticsApiClient,
            zBonApiClient = noZBonYet,
            employeeApiClient = unserved<EmployeeApiClient>(),
            billApiClient = unserved<BillApiClient>(),
            paymentTypeApiClient = unserved<PaymentTypeApiClient>(),
        )

    @Test
    fun `average tip per bill divides the raw total, not the formatted one`() =
        runTest(testDispatcher) {
            val vm = viewModel(statistics(overview(tips = "2.50", bills = 7)))
            vm.onCustomDateRangeSelected("2026-10-01T00:00:00", "2026-10-02T23:59:59")
            advanceUntilIdle()

            assertEquals("0,36 €", vm.uiState.value.averageTipPerBill)
            assertEquals("2,50 €", vm.uiState.value.totalTips)
        }

    @Test
    fun `average tip per bill is zero for a period without bills`() =
        runTest(testDispatcher) {
            val vm = viewModel(statistics(overview(tips = "0.00", bills = 0)))
            vm.onCustomDateRangeSelected("2026-10-01T00:00:00", "2026-10-02T23:59:59")
            advanceUntilIdle()

            assertEquals("0,00 €", vm.uiState.value.averageTipPerBill)
        }

    @Test
    fun `tip earners load on the default Rechnungen tab, across every page`() =
        runTest(testDispatcher) {
            val pages =
                listOf(
                    page(listOf(group("1", "9.00")), hasNext = true, current = 0),
                    page(listOf(group("2", "10.00")), hasNext = false, current = 1),
                )
            val vm = viewModel(statistics(overview("19.00", 6), pages))
            vm.determineViewMode(seesEveryone = true)
            advanceUntilIdle()
            assertEquals(DashboardViewMode.BILLS, vm.dashboardViewMode.value, "the default tab is not Mitarbeiter")
            val beforeLoad = vm.uiState.value
            assertTrue(beforeLoad.employeeGroups.isEmpty(), "the Mitarbeiter list has not been asked for")

            vm.loadTipEarners()
            advanceUntilIdle()

            val afterLoad = vm.uiState.value
            assertEquals(listOf("1", "2"), afterLoad.tipEarners.map { it.employeeNumber })
            assertTrue(afterLoad.employeeGroups.isEmpty(), "the Mitarbeiter list is untouched")
        }

    @Test
    fun `tip earners fetched under an old training-mode filter are discarded`() =
        runTest(testDispatcher) {
            val vm = viewModel(statistics(overview("19.00", 6), listOf(page(listOf(group("1", "9.00"))))))
            vm.determineViewMode(seesEveryone = true)
            advanceUntilIdle()
            // The filter flips while the first page is in flight; the range itself does not move.
            duringGroupPageRequest = { vm.toggleTrainingMode() }

            vm.loadTipEarners()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.trainingMode)
            assertTrue(state.tipEarners.isEmpty(), "the old filter's groups must not land: ${state.tipEarners}")
            assertFalse(state.tipEarnersLoaded)
            assertFalse(state.isLoadingTipEarners)
        }

    @Test
    fun `tip earners are not requested for a cashier`() =
        runTest(testDispatcher) {
            val vm = viewModel(statistics(overview("1.00", 1)))
            vm.determineViewMode(seesEveryone = false)
            advanceUntilIdle()

            vm.loadTipEarners()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.tipEarners.isEmpty())
            assertTrue(groupPagesRequested.isEmpty())
        }

    @Test
    fun `the custom range dialog opens, a chosen range is applied and closes it`() =
        runTest(testDispatcher) {
            val vm = viewModel(statistics(overview("0.00", 0)))
            vm.showCustomDateRangeDialog()
            assertTrue(vm.uiState.value.showCustomDateRangeDialog)

            vm.onCustomDateRangeSelected("2026-10-01T00:00:00", "2026-10-02T23:59:59")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(DateFilterPreset.CUSTOM, state.selectedDatePreset)
            assertEquals("2026-10-01T00:00:00" to "2026-10-02T23:59:59", state.timeRange)
            assertFalse(state.showCustomDateRangeDialog)
        }
}
