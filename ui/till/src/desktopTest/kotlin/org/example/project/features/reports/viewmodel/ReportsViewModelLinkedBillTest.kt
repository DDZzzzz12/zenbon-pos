package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private fun link(id: Long) = BillDto.BillLinkDto(id, "RE-$id")

private fun bill(
    id: Long,
    relations: BillDto.BillRelationsDto = BillDto.BillRelationsDto(),
) = BillDto.BillResponseDto(
    billId = id,
    employeeId = "emp-1",
    employeeNumber = "07",
    tipRecipient = "EMPLOYEE",
    tableSessionId = 500L,
    tableNumber = "12",
    billNumber = "RE-$id",
    billAmount = "10.60",
    paidAmount = "10.60",
    tips = "0.00",
    startTime = "2026-09-27T12:00:00",
    endTime = "2026-09-27T12:00:00",
    orderItems = emptyList(),
    payments = emptyList(),
    relations = relations,
)

/** RE-1 (original) ← RE-2 (receivable clearing), RE-1 ← RE-3 (Storno). */
private val original =
    bill(1, BillDto.BillRelationsDto(isCancelled = true, reversedBy = link(3)))
private val clearing = bill(2, BillDto.BillRelationsDto(kind = BillKind.RECEIVABLE_CLEARING, clearsBill = link(1)))
private val storno = bill(3, BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(1)))

/** A Zahlartwechsel: RE-10 as the panel showed it before, RE-10 as the server holds it after, RE-12 the Neubuchung. */
private val rebookedOriginalBefore = bill(10)
private val rebookedOriginalAfter =
    bill(10, BillDto.BillRelationsDto(isCancelled = true, reversedBy = link(11), rebookedBy = link(12)))
private val rebooking = bill(12, BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = link(10)))

private fun summary(id: Long) =
    BillDto.BillSummaryDto(
        billId = id,
        billNumber = "RE-$id",
        billAmount = "10.60",
        paidAmount = "10.60",
        tips = "0.00",
        endTime = "2026-09-27T12:00:00",
        employeeNumber = "07",
    )

private inline fun <reified T : Any> unexercised(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name} is not used here"))
    } as T

/**
 * Following a related-bill link in the Berichte receipt panel (clearing → original → Storno …)
 * opens the linked bill IN PLACE and remembers where the operator came from.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ReportsViewModelLinkedBillTest {
    /**
     * The last instant of the till's today: an open range ends no earlier (emu19 BIL-07), since the
     * server stamps Belege with its own clock, which may run ahead of the till's.
     */
    private fun endOfTillToday(): String {
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        return LocalDateTime(today, LocalTime(23, 59, 59, 999_999_000)).toString()
    }

    private val testDispatcher = StandardTestDispatcher()
    private val requested = mutableListOf<Long>()
    private val bills = mapOf(1L to original, 2L to clearing, 3L to storno, 10L to rebookedOriginalAfter)

    /** Every call the list reload makes on the Z-Bon/statistics clients, as "Client.method". */
    private val reloadCalls = mutableListOf<String>()

    private inline fun <reified T : Any> recordingReload(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            reloadCalls += "${T::class.simpleName}.${method.name}"
            throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name} is not served here"))
        } as T

    /** Serves [bills] by id and records every id fetched; any other call fails like a transport error. */
    private val billApi: BillApiClient =
        Proxy.newProxyInstance(BillApiClient::class.java.classLoader, arrayOf(BillApiClient::class.java)) { _, method, args ->
            if (method.name != "getBillById") throw NetworkApiException(UnsupportedOperationException(method.name))
            val id = args[0] as Long
            requested += id
            bills[id] ?: throw NetworkApiException(IllegalStateException("no bill $id"))
        } as BillApiClient

    /** A shop with no Z-Bon yet: "since the last Z-Bon" starts at today's midnight and runs to now. */
    private val noZBonYet: ZBonApiClient =
        Proxy.newProxyInstance(ZBonApiClient::class.java.classLoader, arrayOf(ZBonApiClient::class.java)) { _, method, _ ->
            reloadCalls += "ZBonApiClient.${method.name}"
            if (method.name != "getLatestZBonEndTime") throw NetworkApiException(UnsupportedOperationException(method.name))
            null
        } as ZBonApiClient

    private fun viewModel(zBonApiClient: ZBonApiClient = recordingReload<ZBonApiClient>()) =
        ReportsViewModel(
            statisticsApiClient = recordingReload<StatisticsApiClient>(),
            zBonApiClient = zBonApiClient,
            employeeApiClient = unexercised<EmployeeApiClient>(),
            billApiClient = billApi,
            paymentTypeApiClient = unexercised<PaymentTypeApiClient>(),
        )

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a link opens the linked bill in the same panel and back returns to the one it came from`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillSummaryClick(summary(2))
            advanceUntilIdle()
            assertEquals(
                2L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertNull(vm.uiState.value.billTrail.backTo, "the opened bill has nothing to go back to")

            // "Ausgleich zu RE-1" on the clearing
            vm.openLinkedBill(link(1))
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(listOf(2L, 1L), requested, "the link fetches the linked bill by id")
            assertEquals(1L, state.selectedBill?.billId)
            assertTrue(state.showBillPanel, "still the same panel")
            assertEquals(link(2), state.billTrail.backTo, "\"Zurück zu RE-2\"")

            // "Storniert durch RE-3" on the original
            vm.openLinkedBill(link(3))
            advanceUntilIdle()
            assertEquals(
                3L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertEquals(link(1), vm.uiState.value.billTrail.backTo)

            vm.backToPreviousBill()
            assertEquals(
                1L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertEquals(link(2), vm.uiState.value.billTrail.backTo)
            vm.backToPreviousBill()
            assertEquals(
                2L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertNull(vm.uiState.value.billTrail.backTo)
        }

    @Test
    fun `following a link back to a bill already on the trail collapses the trail`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillSummaryClick(summary(1))
            advanceUntilIdle()
            vm.openLinkedBill(link(3)) // "Storniert durch RE-3"
            advanceUntilIdle()
            vm.openLinkedBill(link(1)) // "Storno zu RE-1"
            advanceUntilIdle()

            assertEquals(
                1L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertNull(vm.uiState.value.billTrail.backTo, "RE-1 → RE-3 → RE-1 is back at the start, not two deep")
        }

    @Test
    fun `closing the panel or opening another row forgets the trail`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillSummaryClick(summary(2))
            advanceUntilIdle()
            vm.openLinkedBill(link(1))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.billTrail.backTo)

            vm.dismissBillPanel()
            assertNull(vm.uiState.value.billTrail.backTo)

            vm.onBillSummaryClick(summary(2))
            advanceUntilIdle()
            vm.openLinkedBill(link(1))
            advanceUntilIdle()
            vm.onBillSummaryClick(summary(3))
            advanceUntilIdle()
            assertEquals(
                3L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertNull(vm.uiState.value.billTrail.backTo, "a fresh row starts a fresh trail")
        }

    @Test
    fun `a failed link fetch keeps the current bill open and surfaces the error`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillSummaryClick(summary(2))
            advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            vm.openLinkedBill(BillDto.BillLinkDto(99L, "RE-99"))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(2L, state.selectedBill?.billId)
            assertNull(state.billTrail.backTo)
            assertNotNull(state.error)
            assertTrue(!state.isLoadingBill)
        }

    @Test
    fun `a Zahlartwechsel shows the new bill with the re-read original on the trail and reloads the list`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillFound(rebookedOriginalBefore)
            advanceUntilIdle()
            reloadCalls.clear()

            vm.onBillRebooked(rebooking)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(12L, state.selectedBill?.billId, "the panel shows the Neubuchung")
            assertTrue(state.showBillPanel)
            assertEquals(link(10), state.billTrail.backTo, "\"Zurück zu RE-10\"")
            assertEquals(listOf(10L), requested, "the original is re-read once")
            assertTrue(reloadCalls.isNotEmpty(), "the bill list is reloaded")

            vm.backToPreviousBill()
            val back = vm.uiState.value.selectedBill
            assertEquals(rebookedOriginalAfter, back, "back shows the original as it stands now, not the stale copy")
            assertEquals(link(12), back?.relations?.rebookedBy)
        }

    @Test
    fun `going back before the original is re-read still ends on the re-read original`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillFound(rebookedOriginalBefore)
            advanceUntilIdle()

            vm.onBillRebooked(rebooking)
            vm.backToPreviousBill() // before the re-read has returned
            assertEquals(rebookedOriginalBefore, vm.uiState.value.selectedBill, "for now, the stale copy is on screen")

            advanceUntilIdle()
            assertEquals(rebookedOriginalAfter, vm.uiState.value.selectedBill, "the re-read replaces the bill on screen too")
            assertNull(vm.uiState.value.billTrail.backTo)
        }

    @Test
    fun `a failed Zahlartwechsel re-reads the bill on screen and reloads the list`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onBillFound(rebookedOriginalBefore)
            advanceUntilIdle()
            reloadCalls.clear()

            vm.onRebookFailed(10L)
            advanceUntilIdle()

            assertEquals(listOf(10L), requested, "the bill is re-read")
            assertEquals(rebookedOriginalAfter, vm.uiState.value.selectedBill, "the panel shows the bill as it stands now")
            assertTrue(reloadCalls.isNotEmpty(), "the bill list is reloaded")
        }

    @Test
    fun `a Zahlartwechsel under an open-ended preset keeps the range open to the end of today so the new bills are listed`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onDatePresetSelected(DateFilterPreset.TODAY)
            advanceUntilIdle()
            val before = assertNotNull(vm.uiState.value.timeRange)
            Thread.sleep(5) // the Neubuchung and its Storno are written after the screen fixed its range
            vm.onBillFound(rebookedOriginalBefore)

            vm.onBillRebooked(rebooking)
            advanceUntilIdle()

            val after = assertNotNull(vm.uiState.value.timeRange)
            assertEquals(before.first, after.first, "the range still starts where the preset starts")
            assertTrue(after.second >= endOfTillToday(), "the range must reach past now, not end when the screen opened: $before -> $after")
            assertEquals(DateFilterPreset.TODAY, vm.uiState.value.selectedDatePreset)
        }

    @Test
    fun `a failed Zahlartwechsel under an open-ended preset also keeps the range open to the end of today`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.onDatePresetSelected(DateFilterPreset.TODAY)
            advanceUntilIdle()
            val before = assertNotNull(vm.uiState.value.timeRange)
            Thread.sleep(5)
            vm.onBillFound(rebookedOriginalBefore)

            vm.onRebookFailed(10L)
            advanceUntilIdle()

            val after = assertNotNull(vm.uiState.value.timeRange)
            assertTrue(after.second >= endOfTillToday(), "the committed-then-failed case must list its bills too: $before -> $after")
        }

    @Test
    fun `a Zahlartwechsel under a chosen historical range keeps that range`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            val chosen = "2026-09-01T00:00" to "2026-09-01T23:59:59"
            vm.onCustomDateRangeSelected(chosen.first, chosen.second)
            advanceUntilIdle()
            vm.onBillFound(rebookedOriginalBefore)
            reloadCalls.clear()

            vm.onBillRebooked(rebooking)
            advanceUntilIdle()

            assertEquals(chosen, vm.uiState.value.timeRange, "a past day the operator picked stays picked")
            assertEquals(DateFilterPreset.CUSTOM, vm.uiState.value.selectedDatePreset)
            assertTrue(reloadCalls.isNotEmpty(), "the list is still reloaded")
            assertEquals(listOf(10L), requested, "the original is still re-read")
        }

    @Test
    fun `a Zahlartwechsel under the default since-last-Z-Bon range recomputes it the way re-entering Berichte does`() =
        runTest(testDispatcher) {
            val vm = viewModel(zBonApiClient = noZBonYet)
            vm.loadStatistics() // what entering the screen does
            advanceUntilIdle()
            val before = assertNotNull(vm.uiState.value.timeRange)
            Thread.sleep(5)
            vm.onBillFound(rebookedOriginalBefore)

            vm.onBillRebooked(rebooking)
            advanceUntilIdle()

            val after = assertNotNull(vm.uiState.value.timeRange)
            assertEquals(before.first, after.first)
            assertTrue(after.second >= endOfTillToday(), "the default range must reach past now after a rebook: $before -> $after")
            assertEquals(DateFilterPreset.SINCE_LAST_ZBON, vm.uiState.value.selectedDatePreset)
        }
}
