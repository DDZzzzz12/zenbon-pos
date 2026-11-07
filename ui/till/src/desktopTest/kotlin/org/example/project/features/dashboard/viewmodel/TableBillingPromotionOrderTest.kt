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
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.TableBillingViewModel
import org.example.project.money.Decimal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * QA finding NF-1 (rt41): the server applies ITEM_SPECIFIC promotions before BILL_WIDE ones
 * (`BillServiceImpl`), so a 10 % bill promotion works on the bill *after* the item promotion. The
 * till folded the promotions in the order the cashier picked them: BILL_WIDE first gave 10 % of the
 * full bill, the till asked for more than the server would bill and the payment check refused it
 * ("Payment total … does not match bill total …").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableBillingPromotionOrderTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var vm: TableBillingViewModel

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
                languageManager = LanguageManager(),
            )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.openReviewStep() {
        vm.loadTableData(TABLE_ID)
        advanceUntilIdle()
        vm.loadItems()
        advanceUntilIdle()
        vm.selectAllItems()
    }

    private fun promotion(
        id: Long,
        type: String,
        value: String,
        scope: String,
    ) = PromotionDto.PromotionResponseDto(
        id = id,
        name = "P$id",
        description = null,
        discountType = type,
        discountValue = value,
        scope = scope,
        targetProductId = null,
        targetProductName = null,
        isMultiUse = true,
        validFrom = "2026-01-01T00:00:00",
        validUntil = null,
        isActive = true,
        createdBy = "A001",
        createdAt = "2026-01-01T00:00:00",
    )

    private fun applyBillWidePercentThenItemFixed() {
        vm.addPromotion(PromotionDto.PromotionApplyRequestDto(promotionId = 1L), promotion(1L, "PERCENTAGE", "10.00", "BILL_WIDE"))
        vm.addPromotion(PromotionDto.PromotionApplyRequestDto(promotionId = 2L, orderItemId = 1L), promotion(2L, "FIXED_AMOUNT", "1.00", "ITEM_SPECIFIC"))
    }

    @Test
    fun `a bill-wide percentage picked first is worked out on the bill after the item promotion`() =
        runTest(testDispatcher) {
            openReviewStep()
            applyBillWidePercentThenItemFixed()

            val state = vm.uiState.value
            // Cola 4,50 + Nudeln 6,00 = 10,50; item promotion first: 9,50; 10 % of 9,50 = 0,95.
            assertEquals(
                listOf(1L to d("0.95"), 2L to d("1.00")),
                state.selectedPromotions.map { it.promotionId to it.deductedAmount },
                "shown in the order picked, amounts in the server's order",
            )
            assertEquals(d("8.55"), state.grandTotal, "10,50 - 1,95 = what the server bills")
        }

    @Test
    fun `picking the item promotion first gives the same total`() =
        runTest(testDispatcher) {
            openReviewStep()
            vm.addPromotion(PromotionDto.PromotionApplyRequestDto(promotionId = 2L, orderItemId = 1L), promotion(2L, "FIXED_AMOUNT", "1.00", "ITEM_SPECIFIC"))
            vm.addPromotion(PromotionDto.PromotionApplyRequestDto(promotionId = 1L), promotion(1L, "PERCENTAGE", "10.00", "BILL_WIDE"))

            assertEquals(d("8.55"), vm.uiState.value.grandTotal)
        }

    @Test
    fun `removing the item promotion re-works the percentage on the full bill`() =
        runTest(testDispatcher) {
            openReviewStep()
            applyBillWidePercentThenItemFixed()

            vm.removePromotion(2L)

            assertEquals(
                d("1.05"),
                vm.uiState.value.selectedPromotions
                    .single()
                    .deductedAmount,
            )
            assertEquals(d("9.45"), vm.uiState.value.grandTotal)
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
