package org.example.project.admin.features.zBon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import org.example.project.admin.features.dailyreport.FakeBillApiClient
import org.example.project.admin.features.zBon.presentation.SessionDetailViewModel
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.money.Decimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun session() =
    TableSessionDto.TableSessionResponseDto(
        id = 4786,
        tableNumber = "1",
        employeeId = "emp-1",
        employeeNumber = "K001",
        isDelivery = false,
        customer = null,
        deliveryAddress = null,
        startTime = LocalDateTime(2026, 10, 1, 20, 45),
        endTime = LocalDateTime(2026, 10, 1, 21, 44),
        completed = true,
    )

private fun receipt(
    id: Long,
    paid: String,
    deletingReason: String? = null,
    deletedBillId: String? = null,
    kind: BillKind = BillKind.SALE,
) = BillDto.BillResponseDto(
    billId = id,
    employeeId = "emp-1",
    employeeNumber = "K001",
    tipRecipient = "EMPLOYEE",
    tableSessionId = 4786L,
    tableNumber = "1",
    billNumber = "RE-261001-$id",
    billAmount = paid,
    paidAmount = paid,
    tips = "0.00",
    startTime = "2026-10-01T20:45:00",
    endTime = "2026-10-01T21:44:00",
    deletedBillId = deletedBillId,
    deletingReason = deletingReason,
    orderItems = emptyList(),
    payments = emptyList(),
    relations = BillDto.BillRelationsDto(kind = kind, isCancelled = deletingReason != null && deletedBillId == null),
)

/**
 * admin16 FAIL-2: session 4786 listed only RE-44 (17,90) and the Storno RE-27 (−19,90) of RE-8,
 * and showed "Umsatz −2,00 €". RE-2 (cancelled by a Zahlartwechsel), its Storno RE-7 and the
 * Neubuchung RE-8 (itself stornoed by RE-27) were hidden. The tab now lists all five receipts and
 * the revenue counts active bills only: 17,90.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailRevenueTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun everyReceiptIsListedAndRevenueCountsActiveBillsOnly() =
        runTest(dispatcher) {
            val receipts =
                listOf(
                    receipt(2, "19.90", deletingReason = "RE-261001-7 : Zahlartwechsel"),
                    receipt(7, "-19.90", deletingReason = "Zahlartwechsel", deletedBillId = "2", kind = BillKind.STORNO),
                    receipt(8, "19.90", deletingReason = "RE-261001-27 : falsch", kind = BillKind.REBOOKING),
                    receipt(27, "-19.90", deletedBillId = "8", kind = BillKind.STORNO),
                    receipt(44, "17.90"),
                )
            val bills = FakeBillApiClient(sessionBills = receipts)
            val vm =
                SessionDetailViewModel(
                    sessionId = 4786,
                    sessionApiClient = FakeTableSessionApiClient(session = session()),
                    billApiClient = bills,
                    orderApiClient = FakeOrderApiClient(),
                    orderItemApiClient = FakeOrderItemApiClient(),
                )

            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.billsError)
            assertEquals(listOf(true), bills.sessionCalls, "the session's bills are read with includeDeleted=true")
            assertEquals(listOf(2L, 7L, 8L, 27L, 44L), vm.state.bills.map { it.billId }, "all five receipts are listed")
            assertEquals(0, vm.state.billsRevenue.compareTo(Decimal.parse("17.90")), "revenue = RE-44 only, not −2,00")
        }
}
