package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.CompletableDeferred
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
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A client (or the rest of one) this test does not exercise: every call fails like a transport error. */
private inline fun <reified T : Any> unexercised(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name}"))
    } as T

/**
 * The Tagesbericht filter panel applies several fields in one tap, each setter starting its own
 * reload. Whatever order the summary answers arrive in, the KPI cards must show the LAST request's
 * figures — the one filtered by every field, matching the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelStaleSummaryTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class PendingSummary(
        val trainingOnly: Boolean,
        val answer: CompletableDeferred<StatisticsDto.SalesOverviewResponseDto> = CompletableDeferred(),
    )

    private fun overview(revenue: String) =
        StatisticsDto.SalesOverviewResponseDto(
            period = "DAY",
            totalRevenue = revenue,
            billedRevenue = revenue,
            canceledRevenue = "0.00",
            openItemAmount = "0.00",
            totalTips = "0.00",
        )

    @Test
    fun summaryAnsweredOutOfOrder_keepsTheLatestRequest() =
        runTest(testDispatcher) {
            val pending = mutableListOf<PendingSummary>()
            val statistics =
                object : StatisticsApiClient by unexercised<StatisticsApiClient>() {
                    override suspend fun getSalesOverview(
                        startDate: String,
                        endDate: String,
                        employeeId: String?,
                        trainingOnly: Boolean,
                        compare: String?,
                        granularity: String?,
                    ): StatisticsDto.SalesOverviewResponseDto {
                        val call = PendingSummary(trainingOnly)
                        pending += call
                        return call.answer.await()
                    }
                }
            val zBon =
                object : ZBonApiClient by unexercised<ZBonApiClient>() {
                    override suspend fun getLatestZBonEndTime(): String? = null
                }
            val viewModel =
                ReportsViewModel(
                    statisticsApiClient = statistics,
                    zBonApiClient = zBon,
                    employeeApiClient = unexercised<EmployeeApiClient>(),
                    billApiClient = unexercised<BillApiClient>(),
                    paymentTypeApiClient = unexercised<PaymentTypeApiClient>(),
                )
            viewModel.determineViewMode(seesEveryone = true)
            advanceUntilIdle()
            pending.forEach { it.answer.complete(overview("100.00")) }
            advanceUntilIdle()
            pending.clear()

            // One "Anwenden": two setters, two reloads.
            viewModel.onEmployeeSelected("e1")
            viewModel.toggleTrainingMode()
            advanceUntilIdle()
            assertEquals(2, pending.size, "each setter starts a summary load")
            assertTrue(pending.last().trainingOnly, "the second request carries every field")

            // The later request answers first; the earlier one lands last.
            pending.last().answer.complete(overview("222.00"))
            advanceUntilIdle()
            pending.first().answer.complete(overview("111.00"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("222" in state.totalRevenue, "stale summary overwrote the latest: ${state.totalRevenue}")
            assertEquals(false, state.isLoading)
        }
}
