package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.features.dashboard.presentation.viewmodel.TableOrderHistoryViewModel
import org.example.project.testutils.fakes.StubClientOrderService
import org.example.project.testutils.fakes.StubRTableDataProvider
import org.example.project.ui.screens.dashboard.history.isCancelledOriginal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's table history listed the session's bills with `includeDeleted = false`: a Zahlartwechsel
 * left only the Neubuchung (the cancelled original and its Storno were hidden), unlike Berichte and
 * the back office, which list every Beleg with its relation chip. It now asks for every receipt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderHistoryBillsTest {
    private val testDispatcher = StandardTestDispatcher()

    private val original =
        bill(1, "19.90", deletingReason = "RE-2 : Zahlartwechsel", relations = BillDto.BillRelationsDto(isCancelled = true))
    private val storno =
        bill(
            2,
            "-19.90",
            deletedBillId = "1",
            relations =
                BillDto.BillRelationsDto(
                    kind = BillKind.STORNO,
                    reversesBill = BillDto.BillLinkDto(1, "RE-1"),
                    stornoReason = StornoReason.ZAHLARTWECHSEL,
                ),
        )
    private val rebooking =
        bill(3, "19.90", relations = BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = BillDto.BillLinkDto(1, "RE-1")))

    private val bills = SessionBillApiClient(listOf(original, storno, rebooking))

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the session's bills are loaded with includeDeleted and list original, Storno and Neubuchung`() =
        runTest(testDispatcher) {
            val vm =
                TableOrderHistoryViewModel(
                    orderApiClient = EmptyOrderApiClient(),
                    billApiClient = bills,
                    rTableDataProvider = OneTableDataProvider(),
                    orderService = StubClientOrderService(),
                    orderItemApiClient = EmptyOrderItemApiClient(),
                )

            vm.loadOrderHistory(tableId = 7)
            advanceUntilIdle()

            assertEquals(listOf(true), bills.includeDeletedCalls, "the till asks for every receipt of the session")
            assertEquals(
                listOf(1L, 2L, 3L),
                vm.uiState.value.bills
                    .map { it.billId },
            )
        }

    @Test
    fun `only the cancelled original is marked as no longer standing`() {
        assertTrue(isCancelledOriginal(original))
        assertFalse(isCancelledOriginal(storno), "a Storno is never itself cancelled")
        assertFalse(isCancelledOriginal(rebooking))
        assertTrue(isCancelledOriginal(bill(4, "5.00", deletingReason = "x")), "raw column alone, no relations")
    }
}

private fun bill(
    id: Long,
    amount: String,
    deletedBillId: String? = null,
    deletingReason: String? = null,
    relations: BillDto.BillRelationsDto = BillDto.BillRelationsDto(),
) = BillDto.BillResponseDto(
    billId = id,
    employeeId = "emp-1",
    employeeNumber = "K001",
    tipRecipient = "EMPLOYEE",
    tableSessionId = 99L,
    tableNumber = "7",
    billNumber = "RE-$id",
    billAmount = amount,
    paidAmount = amount,
    tips = "0.00",
    startTime = "2026-10-01T20:00:00",
    endTime = "2026-10-01T20:10:00",
    deletedBillId = deletedBillId,
    deletingReason = deletingReason,
    orderItems = emptyList(),
    payments = emptyList(),
    relations = relations,
)

/** Any call the test does not expect fails loudly. */
private inline fun <reified T> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("unexpected call: ${method.name}")
    } as T

/** Answers like the server: deleted bills only when asked for. */
private class SessionBillApiClient(
    private val all: List<BillDto.BillResponseDto>,
) : BillApiClient by unused() {
    val includeDeletedCalls = mutableListOf<Boolean>()

    override suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> {
        includeDeletedCalls += includeDeleted
        return if (includeDeleted) all else all.filter { it.deletingReason == null }
    }
}

private class OneTableDataProvider : RTableDataProvider by StubRTableDataProvider() {
    override suspend fun getRTableById(id: Long): RTable =
        RTable(
            id = id,
            tableNumber = "7",
            seats = 4,
            locationTypeId = 1,
            locationTypeName = "Innen",
            tableStatus = "OCCUPIED",
            currentSessionId = 99L,
            gridX = null,
            gridY = null,
            rotation = null,
        )
}

private class EmptyOrderApiClient : OrderApiClient by unused() {
    override suspend fun getOrdersByTableSessionId(tableSessionId: Long): List<OrderDto.OrderResponseDto> = emptyList()
}

private class EmptyOrderItemApiClient : OrderItemApiClient by unused() {
    override suspend fun getTransferHistory(tableSessionId: Long): List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto> = emptyList()
}
