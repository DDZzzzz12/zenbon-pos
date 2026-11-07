package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.TableBillingViewModel
import org.example.project.money.Decimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F65: the payment step's "Bezahlt · Verbleibend · Gesamt · davon Trinkgeld" line. 11,00 € tendered
 * on a 10,50 € bill (Cola 4,50 + Gebratene Nudeln 6,00) read "Gesamt: 11,00 €" — the amount paid —
 * while the receipt printed "SUMME: 10,50". "Gesamt" is the bill; the tip is its own part of the line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableBillingPaymentTotalsTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var vm: TableBillingViewModel
    private val languageManager = LanguageManager()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vm =
            TableBillingViewModel(
                rTableDataProvider = OneTableProvider(),
                clientOrderService = TwoItemOrderService(),
                paymentTypeDataProvider = CashOnlyPaymentTypes(),
                billApiClient = RecordingBillApiClient(),
                voucherApiClient = NoOpVoucherApiClient(),
                settingManager = SettingManager(FakeKeyValueStore(mapOf("currency" to "EUR"))),
                promotionApiClient = NoOpPromotionApiClient(),
                languageManager = languageManager,
            )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.openPaymentStep() {
        vm.loadTableData(TABLE_ID)
        advanceUntilIdle()
        vm.loadItems()
        advanceUntilIdle()
        vm.selectAllItems()
        vm.goToPaymentStep()
    }

    @Test
    fun `an overpayment leaves Gesamt at the bill and shows the tip separately`() =
        runTest(testDispatcher) {
            openPaymentStep()
            assertEquals(d("10.50"), vm.uiState.value.grandTotal, "before tendering")

            vm.addPayment(CASH, d("11.00"))

            val state = vm.uiState.value
            assertEquals(d("10.50"), state.grandTotal, "Gesamt is the bill, not the 11,00 paid")
            assertEquals(d("0.50"), state.tipAmount, "davon Trinkgeld")
            assertEquals(d("11.00"), state.paidAmount)
            assertEquals(Decimal.ZERO, state.remainingAmount)
            assertTrue(vm.isPaymentComplete())
        }

    @Test
    fun `part way through tendering paid plus remaining is the bill`() =
        runTest(testDispatcher) {
            openPaymentStep()
            vm.addPayment(CASH, d("5.00"))

            val state = vm.uiState.value
            assertEquals(d("10.50"), state.grandTotal)
            assertEquals(d("5.50"), state.remainingAmount)
            assertEquals(Decimal.ZERO, state.tipAmount)
        }

    @Test
    fun `Verbleibend never goes negative`() =
        runTest(testDispatcher) {
            openPaymentStep()
            listOf("10.50", "0.01", "50.00").forEach { amount ->
                vm.addPayment(CASH, d(amount))
                assertTrue(vm.uiState.value.remainingAmount >= Decimal.ZERO, "after another $amount")
            }
            assertEquals(d("10.50"), vm.uiState.value.grandTotal)
            assertEquals(d("50.01"), vm.uiState.value.tipAmount)
        }

    /**
     * UI review follow-up 7: a tender added while settling is named in the till's UI language (the
     * local payment type's translation, else its German name), not always the German master name.
     */
    @Test
    fun `a payment entry is named in the UI language`() =
        runTest(testDispatcher) {
            openPaymentStep()
            languageManager.setLanguage(Language.ENGLISH)
            vm.addPayment(CASH, d("1.00"))
            assertEquals(
                "CASH",
                vm.uiState.value.payments
                    .single()
                    .paymentTypeName,
            )
        }

    @Test
    fun `a payment entry falls back to the German name without a translation`() =
        runTest(testDispatcher) {
            openPaymentStep()
            languageManager.setLanguage(Language.CHINESE)
            vm.addPayment(CASH, d("1.00"))
            vm.addPayment(CARD, d("1.00"))
            assertEquals(
                listOf("现金", "EC-KARTE"),
                vm.uiState.value.payments
                    .map { it.paymentTypeName },
                "blank Chinese name",
            )
        }

    private fun d(value: String) = Decimal.parse(value)

    private companion object {
        const val TABLE_ID = 7L
        const val SESSION_ID = 70L
        const val CASH = 1L
        const val CARD = 2L
    }

    private class OneTableProvider : RTableDataProvider {
        private val table =
            RTable(
                id = TABLE_ID,
                tableNumber = "T1",
                seats = 4,
                locationTypeId = 1,
                locationTypeName = "Innen",
                tableStatus = "OCCUPIED",
                currentSessionId = SESSION_ID,
                gridX = null,
                gridY = null,
                rotation = null,
            )

        override suspend fun getAllRTables() = listOf(table)

        override suspend fun getRTablesByLocationTypeId(
            locationTypeId: Long,
            includeInactive: Boolean,
        ) = listOf(table)

        override suspend fun getRTableById(id: Long): RTable? = table.takeIf { it.id == id }

        override suspend fun searchRTables(query: String) = emptyList<RTable>()

        override suspend fun refresh(
            locationTypeId: Long,
            includeInactive: Boolean,
        ) {}

        override suspend fun ensureSeeded(
            locationTypeId: Long,
            includeInactive: Boolean,
        ) {}

        override fun resetSeed() {}
    }

    private class TwoItemOrderService : ClientOrderService {
        override suspend fun submitOrder(
            sessionId: Long?,
            items: List<Items>,
            tableId: Long?,
            forEmployeeNumber: String?,
            expectedNextEmployeeId: String?,
        ): OrderDto.OrderResponseDto = error("unused")

        override suspend fun getOrderItemsForTable(tableId: Long) =
            listOf(
                item(id = 1L, name = "Cola", price = "4.50", vatClass = "A", vatRate = "19.00"),
                item(id = 2L, name = "Gebratene Nudeln", price = "6.00", vatClass = "B", vatRate = "7.00"),
            )

        override suspend fun cancelOrderItems(
            itemIds: List<Long>,
            reason: String?,
            sourceTableId: Long,
        ) = ApprovalOutcome.COMPLETED

        override suspend fun transferOrderItems(
            itemIds: List<Long>,
            targetTableId: Long,
            sourceTableId: Long,
            reason: String,
        ) = ApprovalOutcome.COMPLETED

        private fun item(
            id: Long,
            name: String,
            price: String,
            vatClass: String,
            vatRate: String,
        ) = OrderItemDto.OrderItemResponseDto(
            orderItemIds = listOf(id),
            ordersId = setOf(1L),
            product =
                ProductDto.OrderItemProductDto(
                    productId = id,
                    productNumber = id.toString(),
                    productName = name,
                    en = null,
                    ch = null,
                    productType = "FOOD",
                ),
            unitPrice = price,
            finalPrice = price,
            chargedUnitPrice = price,
            itemStatus = "OPEN",
            vatClass = vatClass,
            vatRate = vatRate,
            customPrice = false,
            discount = 1.0,
            takeAway = false,
            transferred = false,
            quantity = 1,
        )
    }

    private class CashOnlyPaymentTypes : PaymentTypeDataProvider {
        private val bar = PaymentType(id = CASH, name = "BAR", enName = "CASH", chName = "现金", isActive = true)

        /** No English translation, a blank Chinese one: both fall back to the German name. */
        private val ec = PaymentType(id = CARD, name = "EC-KARTE", enName = null, chName = " ", isActive = true)

        override suspend fun getAllPaymentTypes() = listOf(bar, ec)

        override suspend fun getActivePaymentTypes() = listOf(bar, ec)

        override suspend fun getPaymentTypeById(id: Long): PaymentType? = listOf(bar, ec).firstOrNull { it.id == id }
    }
}
