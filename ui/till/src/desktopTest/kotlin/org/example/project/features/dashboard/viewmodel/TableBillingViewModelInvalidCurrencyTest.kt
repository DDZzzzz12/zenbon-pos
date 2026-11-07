package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.models.RTable
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.viewmodel.TableBillingViewModel
import org.example.project.money.CurrencyFormatException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the invalid-currency error routing in [TableBillingViewModel].
 *
 * `resolveCurrency()` must never fall back to a default when the configured currency is
 * malformed — it routes to the billing error path instead, and [TableBillingViewModel.confirmBill]
 * refuses to submit a bill whose totals would be computed against the wrong currency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableBillingViewModelInvalidCurrencyTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var billApiClient: RecordingBillApiClient

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        billApiClient = RecordingBillApiClient()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid configured currency routes confirmBill to the billing error path`() {
        // "EURO" is four letters — Currency rejects anything but exactly 3 uppercase ASCII letters.
        val viewModel = viewModelWithCurrency("EURO")

        viewModel.confirmBill()

        val error = viewModel.uiState.value.error
        assertNotNull(error, "an invalid currency must surface as a billing error, not be ignored")
        assertIs<UiError.Unknown>(error)
        assertEquals(CommonKeys.unknownErrorTitle, error.titleKey)
        assertEquals(BillingKeys.errorOccurred, error.messageKey)
        assertIs<CurrencyFormatException>(error.cause)
        assertFalse(billApiClient.createBillCalled, "confirmBill must not submit with an unresolvable currency")
    }

    @Test
    fun `valid currency passes the currency gate`() {
        val viewModel = viewModelWithCurrency("EUR")

        viewModel.confirmBill()

        val error = viewModel.uiState.value.error
        // The currency gate is passed; the payment-complete gate fires instead (no payments set),
        // which is a distinct error with no CurrencyFormatException cause.
        assertNotNull(error, "payment gate should fire once the currency gate passes")
        assertIs<UiError.Unknown>(error)
        // Both gates emit UiError.Unknown with the same titleKey; the messageKey distinguishes
        // the payment-complete gate (unknownErrorMessage) from the currency gate (errorOccurred).
        assertEquals(CommonKeys.unknownErrorMessage, error.messageKey)
        assertNull(error.cause, "a valid currency must not be misreported as a parse failure")
        assertFalse(billApiClient.createBillCalled)
    }

    private fun viewModelWithCurrency(currencyCode: String): TableBillingViewModel =
        TableBillingViewModel(
            rTableDataProvider = NoOpRTableDataProvider(),
            clientOrderService = NoOpClientOrderService(),
            paymentTypeDataProvider = NoOpPaymentTypeDataProvider(),
            billApiClient = billApiClient,
            voucherApiClient = NoOpVoucherApiClient(),
            settingManager = SettingManager(FakeKeyValueStore(mapOf("currency" to currencyCode))),
            promotionApiClient = NoOpPromotionApiClient(),
            languageManager = LanguageManager(),
        )
}

/** In-memory [KeyValueStore] seeded from a map, so SettingManager reads the injected currency. */
internal class FakeKeyValueStore(
    private val values: Map<String, String>,
) : KeyValueStore {
    override fun getString(key: String): String? = values[key]

    override fun setString(
        key: String,
        value: String,
    ) = error("not needed in this test")

    override fun remove(key: String) = error("not needed in this test")

    override fun getAll(): Map<String, String> = values

    override fun clear() = error("not needed in this test")
}

/** Records whether the bill submission was reached — must stay false for the invalid-currency path. */
internal class RecordingBillApiClient : BillApiClient {
    var createBillCalled = false
        private set

    override suspend fun createBill(request: BillDto.CreateBillRequestDto): BillDto.BillResponseDto {
        createBillCalled = true
        throw NotImplementedError()
    }

    override suspend fun getBillById(billId: Long): Nothing = throw NotImplementedError()

    override suspend fun getBillByNumber(billNumber: String): Nothing = throw NotImplementedError()

    override suspend fun getBillByTseTransactionNumber(transactionNumber: Long): Nothing = throw NotImplementedError()

    override suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean,
    ): Nothing = throw NotImplementedError()

    override suspend fun deleteBill(
        billId: Long,
        deletingReason: String?,
        restoreSession: Boolean,
    ): Nothing = throw NotImplementedError()

    override suspend fun rebookBill(
        billId: Long,
        request: BillDto.RebookRequestDto,
    ): BillDto.BillResponseDto = throw NotImplementedError()

    override suspend fun printBill(
        billId: Long,
        bewirtungsbeleg: Boolean,
    ) = error("not needed in this test")

    override suspend fun getAllBills(includeDeleted: Boolean): Nothing = throw NotImplementedError()

    override suspend fun search(
        employeeNumber: String?,
        tableSessionId: Long?,
        startDate: String?,
        endDate: String?,
        minAmount: Double?,
        maxAmount: Double?,
        tipRecipient: String?,
        includeDeleted: Boolean,
        offset: Int,
        limit: Int,
        includeTraining: Boolean,
    ): Nothing = throw NotImplementedError()

    override suspend fun getSummaries(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean,
    ): Nothing = throw NotImplementedError()

    override suspend fun getStatistics(
        startDate: String?,
        endDate: String?,
        includeDeleted: Boolean,
    ): Nothing = throw NotImplementedError()
}

private class NoOpRTableDataProvider : RTableDataProvider {
    override suspend fun getAllRTables() = emptyList<RTable>()

    override suspend fun getRTablesByLocationTypeId(
        locationTypeId: Long,
        includeInactive: Boolean,
    ) = emptyList<RTable>()

    override suspend fun getRTableById(id: Long): RTable? = null

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

private class NoOpClientOrderService : ClientOrderService {
    override suspend fun submitOrder(
        sessionId: Long?,
        items: List<Items>,
        tableId: Long?,
        forEmployeeNumber: String?,
        expectedNextEmployeeId: String?,
    ): OrderDto.OrderResponseDto = error("unused")

    override suspend fun getOrderItemsForTable(tableId: Long) = emptyList<OrderItemDto.OrderItemResponseDto>()

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
}

private class NoOpPaymentTypeDataProvider : PaymentTypeDataProvider {
    override suspend fun getAllPaymentTypes() = emptyList<PaymentType>()

    override suspend fun getActivePaymentTypes() = emptyList<PaymentType>()

    override suspend fun getPaymentTypeById(id: Long): PaymentType? = null
}

internal class NoOpVoucherApiClient : VoucherApiClient {
    override suspend fun createVoucher(request: VoucherDto.CreateVoucherRequestDto): Nothing = throw NotImplementedError()

    override suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): Nothing = throw NotImplementedError()

    override suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ) = emptyList<VoucherDto.VoucherResponseDto>()

    override suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ) = emptyList<VoucherDto.VoucherRedemptionSummaryDto>()

    override suspend fun getVoucherStatistics(): Nothing = throw NotImplementedError()

    override suspend fun getActiveVouchers(
        limit: Int?,
        offset: Int,
    ): Nothing = throw NotImplementedError()

    override suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int?,
        offset: Int,
    ): Nothing = throw NotImplementedError()

    override suspend fun getRedemptionHistory(voucherNumber: String): Nothing = throw NotImplementedError()
}

internal class NoOpPromotionApiClient : PromotionApiClient {
    override suspend fun getActivePromotions() = emptyList<PromotionDto.PromotionResponseDto>()

    override suspend fun getAllPromotions() = emptyList<PromotionDto.PromotionResponseDto>()

    override suspend fun getPromotionById(id: Long): Nothing = throw NotImplementedError()

    override suspend fun createPromotion(request: PromotionDto.CreatePromotionRequestDto): Nothing = throw NotImplementedError()

    override suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): Nothing = throw NotImplementedError()

    override suspend fun deactivatePromotion(id: Long) {}

    override suspend fun getPromotionUsage(id: Long): Nothing = throw NotImplementedError()
}
