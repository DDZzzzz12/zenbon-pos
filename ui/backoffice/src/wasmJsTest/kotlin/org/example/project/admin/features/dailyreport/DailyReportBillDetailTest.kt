package org.example.project.admin.features.dailyreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.dashboard.FakeStatisticsApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.staff.FakeEmployeeApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.example.project.testutils.fakes.FakePaymentTypeApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The row the Tagesbericht list actually holds: a summary, with no order items and no payments. */
private fun summary(billId: Long) =
    BillDto.BillSummaryDto(
        billId = billId,
        billNumber = "B-000$billId",
        billAmount = "16.90",
        paidAmount = "16.90",
        tips = "0.00",
        endTime = "2026-09-16T19:41:00",
        employeeNumber = "07",
        tableNumber = "12",
    )

/** What the by-id fetch returns: the full receipt the detail panel needs. */
private fun fullBill(billId: Long) =
    BillDto.BillResponseDto(
        billId = billId,
        employeeId = "emp-1",
        employeeNumber = "07",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 500L,
        tableNumber = "12",
        billNumber = "B-000$billId",
        billAmount = "16.90",
        paidAmount = "16.90",
        tips = "0.00",
        startTime = "2026-09-16T18:02:00",
        endTime = "2026-09-16T19:41:00",
        orderItems = emptyList(),
        payments = emptyList(),
    )

/**
 * Opening a receipt from the Tagesbericht.
 *
 * The list row is a [BillDto.BillSummaryDto] and the detail panel takes a
 * [BillDto.BillResponseDto], so the tap always costs a by-id fetch. These pin the three states the
 * screen renders off — in flight, opened, failed — because the bug being fixed was a tap that did
 * nothing, and a tap that appears to do nothing while a slow fetch runs is the same defect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReportBillDetailTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var bills: FakeBillApiClient
    private lateinit var vm: ReportsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        bills = FakeBillApiClient(bill = fullBill(42L))
        vm =
            ReportsViewModel(
                statisticsApiClient = FakeStatisticsApiClient(overview = { throw NotImplementedError() }),
                zBonApiClient = FakeZBonApiClient(),
                employeeApiClient = FakeEmployeeApiClient(),
                billApiClient = bills,
                paymentTypeApiClient = FakePaymentTypeApiClient(),
            )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun tappingARow_fetchesTheFullBillByIdAndOpensThePanel() =
        runTest(dispatcher) {
            vm.onBillSummaryClick(summary(42L))
            advanceUntilIdle()

            assertEquals(listOf(42L), bills.requestedIds, "the summary's billId must drive the fetch")
            val state = vm.uiState.value
            assertTrue(state.showBillPanel)
            assertEquals(42L, assertNotNull(state.selectedBill).billId)
            assertFalse(state.isLoadingBill)
            assertNull(state.error)
        }

    @Test
    fun whileTheFetchIsInFlight_theScreenHasALoadingStateToRender() =
        runTest(dispatcher) {
            bills.delayMs = 1_000
            vm.onBillSummaryClick(summary(42L))
            advanceTimeBy(100)

            assertTrue(vm.uiState.value.isLoadingBill, "a slow fetch must not look like a dead tap")
            assertFalse(vm.uiState.value.showBillPanel)

            advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoadingBill)
            assertTrue(vm.uiState.value.showBillPanel)
        }

    /**
     * The loading flag drives a non-dismissible overlay, so a failure that left it standing would
     * lock the screen — worse than the no-op this change replaced.
     */
    @Test
    fun aFailedFetch_surfacesAnErrorAndLowersTheLoadingFlag() =
        runTest(dispatcher) {
            bills.failure = NetworkApiException(RuntimeException("offline"))
            vm.onBillSummaryClick(summary(42L))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNotNull(state.error)
            assertFalse(state.isLoadingBill)
            assertFalse(state.showBillPanel, "a failed fetch must not open an empty panel")
            assertNull(state.selectedBill)
        }

    /**
     * A Storno's receipt links to its original ("Storno zu B-0001"); following it opens the original
     * in the same panel, and back returns to the Storno.
     */
    @Test
    fun followingARelatedBillLink_opensItInPlace_andBackReturns() =
        runTest(dispatcher) {
            val original = fullBill(1L)
            val storno =
                fullBill(2L).copy(
                    relations =
                        BillDto.BillRelationsDto(
                            kind = BillKind.STORNO,
                            reversesBill = BillDto.BillLinkDto(1L, original.billNumber),
                        ),
                )
            bills.billsById = mapOf(1L to original, 2L to storno)
            vm.onBillSummaryClick(summary(2L))
            advanceUntilIdle()

            vm.openLinkedBill(
                assertNotNull(
                    vm.uiState.value.selectedBill
                        ?.relations
                        ?.reversesBill,
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf(2L, 1L), bills.requestedIds)
            assertEquals(
                1L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertTrue(vm.uiState.value.showBillPanel)
            assertEquals(BillDto.BillLinkDto(2L, "B-0002"), vm.uiState.value.billTrail.backTo)

            vm.backToPreviousBill()
            assertEquals(
                2L,
                vm.uiState.value.selectedBill
                    ?.billId,
            )
            assertNull(vm.uiState.value.billTrail.backTo)
        }

    @Test
    fun closingThePanel_dropsTheSelectedBill() =
        runTest(dispatcher) {
            vm.onBillSummaryClick(summary(42L))
            advanceUntilIdle()
            vm.dismissBillPanel()

            assertFalse(vm.uiState.value.showBillPanel)
            assertNull(vm.uiState.value.selectedBill)
        }
}
