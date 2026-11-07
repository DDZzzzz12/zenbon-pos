package org.example.project.admin.features.zBon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.dailyreport.FakeBillApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.zBon.presentation.ZBonHubViewModel
import org.example.project.core.data.remote.BILL_RECEIPTS_PAGE_SIZE
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.report.ZBonDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun zBon() =
    ZBonDto.ZBonResponseDto(
        id = 166,
        shopInfoId = 1,
        shopName = "Testladen",
        employeeNumber = "M001",
        startTime = "2026-09-27T21:58:00",
        endTime = "2026-10-01T21:47:00",
        cancelledAmount = "0.00",
        totalRevenue = "258.52",
        openingBalance = "0.00",
        expenses = "0.00",
        closingBalance = "0.00",
        createdAt = "2026-10-01T21:47:00",
    )

private fun receipt(
    id: Long,
    deletingReason: String? = null,
    deletedBillId: String? = null,
    isTraining: Boolean = false,
) = BillDto.BillResponseDto(
    billId = id,
    employeeId = "emp-1",
    employeeNumber = "K001",
    tipRecipient = "EMPLOYEE",
    tableSessionId = 1L,
    tableNumber = "1",
    billNumber = "RE-261001-$id",
    billAmount = "10.00",
    paidAmount = "10.00",
    tips = "0.00",
    startTime = "2026-10-01T20:00:00",
    endTime = "2026-10-01T20:10:00",
    deletedBillId = deletedBillId,
    deletingReason = deletingReason,
    orderItems = emptyList(),
    payments = emptyList(),
    isTraining = isTraining,
)

/**
 * admin16 FAIL-1: the Z-Bon "Rechnungen" tab showed 24 of #154's 46 receipts — every Zahlartwechsel
 * Storno and cancelled original was hidden by the default `includeDeleted=false` — and stopped at
 * the default page of 50 (#146: 50 of 78). The tab must list every Beleg of the Z-Bon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonHubBillsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun hub(bills: FakeBillApiClient) =
        ZBonHubViewModel(
            zbonId = 166,
            zBonApiClient = FakeZBonApiClient(byId = { zBon() }),
            billApiClient = bills,
            sessionApiClient = FakeTableSessionApiClient(),
        )

    @Test
    fun stornosAndCancelledOriginalsAreListed() =
        runTest(dispatcher) {
            val all =
                listOf(
                    receipt(1),
                    receipt(3, deletingReason = "RE-261001-9 : Zahlartwechsel"),
                    receipt(9, deletingReason = "Zahlartwechsel", deletedBillId = "3"),
                    receipt(10),
                    receipt(36, deletingReason = "RE-261001-37 : falsch"),
                    receipt(37, deletedBillId = "36"),
                )
            val bills = FakeBillApiClient(searchResults = all)
            val vm = hub(bills)

            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.billsError)
            assertEquals(all.map { it.billId }, vm.state.bills.map { it.billId }, "every Beleg of the Z-Bon, Stornos included")
            assertTrue(bills.searchCalls.all { it.first }, "every page must ask for deleted bills too")
        }

    // emu20 NOTE-t: bill.zbon_id and DSFinV-K carry the closing's training receipts; the list did not.
    @Test
    fun trainingReceiptsAreListedAndFlagged() =
        runTest(dispatcher) {
            val all = listOf(receipt(1), receipt(2, isTraining = true), receipt(3))
            val bills = FakeBillApiClient(searchResults = all)
            val vm = hub(bills)

            vm.load()
            advanceUntilIdle()

            assertEquals(all.map { it.billId }, vm.state.bills.map { it.billId }, "every signed Beleg, training included")
            assertTrue(bills.searchTrainingFlags.isNotEmpty() && bills.searchTrainingFlags.all { it }, "every page must ask for training receipts")
            assertTrue(
                vm.state.bills
                    .single { it.billId == 2L }
                    .isTraining,
                "the training receipt keeps its flag for the badge",
            )
        }

    @Test
    fun aZBonWithMoreThanOnePageLoadsEveryReceipt() =
        runTest(dispatcher) {
            // #146: 78 receipts — more than the old default page of 50.
            val all = (1L..78L).map { receipt(it) }
            val bills = FakeBillApiClient(searchResults = all)
            val vm = hub(bills)

            vm.load()
            advanceUntilIdle()

            assertEquals(78, vm.state.bills.size, "no silent cap at one page")
            assertEquals(all.map { it.billId }, vm.state.bills.map { it.billId })
        }

    @Test
    fun anExactMultipleOfThePageSizeStopsOnTheEmptyPage() =
        runTest(dispatcher) {
            val all = (1L..(2L * BILL_RECEIPTS_PAGE_SIZE)).map { receipt(it) }
            val bills = FakeBillApiClient(searchResults = all)
            val vm = hub(bills)

            vm.load()
            advanceUntilIdle()

            assertEquals(all.size, vm.state.bills.size)
            assertEquals(
                listOf(0, BILL_RECEIPTS_PAGE_SIZE, 2 * BILL_RECEIPTS_PAGE_SIZE),
                bills.searchCalls.map { it.second },
                "two full pages, then one empty page ends the walk",
            )
        }
}
