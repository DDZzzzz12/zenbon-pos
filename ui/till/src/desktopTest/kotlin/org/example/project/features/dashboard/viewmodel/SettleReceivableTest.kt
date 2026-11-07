package org.example.project.features.dashboard.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The till's settle-receivable action is one server call, and the server owns every rule. These pin
 * that a success surfaces the returned open amount, and that a refusal surfaces the error and leaves
 * the open-amount state null — a receivable the server did not touch must not read as settled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettleReceivableTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakeSalesInvoiceApiClient : SalesInvoiceApiClient {
        var settleThrows: Throwable? = null
        var settleResult: SalesInvoiceDto.ResponseDto = response(openAmount = "30.00")
        var lastRequest: SalesInvoiceDto.SettleAtTillRequestDto? = null

        override suspend fun list(
            status: String?,
            type: String?,
            open: Boolean,
            overdue: Boolean,
            debtorId: Long?,
            limit: Int,
            offset: Long,
        ): SalesInvoiceDto.ListResponseDto = TODO()

        override suspend fun get(id: Long): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun createFromBill(request: SalesInvoiceDto.CreateFromBillRequestDto): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun createStandalone(request: SalesInvoiceDto.CreateStandaloneRequestDto): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun updateStandalone(
            id: Long,
            request: SalesInvoiceDto.UpdateStandaloneRequestDto,
        ): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun deleteStandalone(id: Long) = TODO()

        override suspend fun issue(id: Long): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun settleAtTill(
            id: Long,
            request: SalesInvoiceDto.SettleAtTillRequestDto,
        ): SalesInvoiceDto.ResponseDto {
            lastRequest = request
            settleThrows?.let { throw it }
            return settleResult
        }

        override suspend fun cancel(
            id: Long,
            request: SalesInvoiceDto.CancelRequestDto,
        ): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun pdf(id: Long): ByteArray = TODO()

        override suspend fun datevExportStatistics(
            start: String,
            end: String,
        ): DatevArExportDto.ArExportStatisticsDto = TODO()

        override suspend fun datevExport(
            start: String,
            end: String,
        ): ByteArray = TODO()

        companion object {
            fun response(openAmount: String) =
                SalesInvoiceDto.ResponseDto(
                    id = 7L,
                    invoiceNumber = "RG-2026-00001",
                    status = "ISSUED",
                    revenueOrigin = "POS_RECEIVABLE",
                    invoiceTypeCode = "380",
                    precedingInvoiceId = null,
                    billId = 42L,
                    debtorId = null,
                    buyerName = "Catering Nord GmbH",
                    buyerVatId = null,
                    buyerStreet = null,
                    buyerZip = null,
                    buyerCity = null,
                    buyerCountry = null,
                    buyerEmail = null,
                    sellerName = null,
                    sellerVatId = null,
                    sellerTaxNumber = null,
                    issueDate = "2026-08-18T12:00",
                    serviceDate = "2026-08-18T12:00",
                    dueDate = null,
                    currency = "EUR",
                    lineNetTotal = "67.23",
                    taxTotal = "12.77",
                    taxInclusiveTotal = "80.00",
                    prepaidAmount = "0.00",
                    amountDue = "80.00",
                    openAmount = openAmount,
                    notes = null,
                    paymentTermsText = null,
                    revenueAccountNo = null,
                    buCode = null,
                    documentId = null,
                    issuedAt = null,
                    cancelledAt = null,
                    lines = emptyList(),
                    vatBreakdown = emptyList(),
                )
        }
    }

    private class StubBillApiClient : BillApiClient {
        override suspend fun getBillById(billId: Long): BillDto.BillResponseDto = TODO()

        override suspend fun getBillByNumber(billNumber: String): BillDto.BillResponseDto = TODO()

        override suspend fun getBillByTseTransactionNumber(transactionNumber: Long): BillDto.BillResponseDto = TODO()

        override suspend fun createBill(request: BillDto.CreateBillRequestDto): BillDto.BillResponseDto = TODO()

        override suspend fun getBillsByTableSessionId(
            tableSessionId: Long,
            includeDeleted: Boolean,
        ): List<BillDto.BillResponseDto> = TODO()

        override suspend fun deleteBill(
            billId: Long,
            deletingReason: String?,
            restoreSession: Boolean,
        ): String = TODO()

        override suspend fun rebookBill(
            billId: Long,
            request: BillDto.RebookRequestDto,
        ): BillDto.BillResponseDto = TODO()

        override suspend fun printBill(
            billId: Long,
            bewirtungsbeleg: Boolean,
        ) = TODO()

        override suspend fun getAllBills(includeDeleted: Boolean): List<BillDto.BillResponseDto> = TODO()

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
        ): List<BillDto.BillResponseDto> = TODO()

        override suspend fun getSummaries(
            offset: Int,
            limit: Int,
            includeDeleted: Boolean,
        ): List<BillDto.BillSummaryDto> = TODO()

        override suspend fun getStatistics(
            startDate: String?,
            endDate: String?,
            includeDeleted: Boolean,
        ): BillDto.BillStatisticsDto = TODO()
    }

    private class StubVoucherApiClient : VoucherApiClient {
        override suspend fun createVoucher(request: VoucherDto.CreateVoucherRequestDto): VoucherDto.VoucherResponseDto = TODO()

        override suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): VoucherDto.ValidateVoucherResponseDto = TODO()

        override suspend fun getVouchersCreatedInRange(
            startDate: String,
            endDate: String,
            employeeId: String?,
        ): List<VoucherDto.VoucherResponseDto> = TODO()

        override suspend fun getVouchersRedeemedInRange(
            startDate: String,
            endDate: String,
            employeeId: String?,
        ): List<VoucherDto.VoucherRedemptionSummaryDto> = TODO()

        override suspend fun getVoucherStatistics(): VoucherDto.VoucherStatisticsDto = TODO()

        override suspend fun getActiveVouchers(
            limit: Int?,
            offset: Int,
        ): List<VoucherDto.VoucherResponseDto> = TODO()

        override suspend fun searchVouchersByNumber(
            searchTerm: String,
            limit: Int?,
            offset: Int,
        ): List<VoucherDto.VoucherResponseDto> = TODO()

        override suspend fun getRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto = TODO()
    }

    private class StubPaymentTypeDataProvider : PaymentTypeDataProvider {
        override suspend fun getAllPaymentTypes(): List<PaymentType> = emptyList()

        override suspend fun getActivePaymentTypes(): List<PaymentType> = emptyList()

        override suspend fun getPaymentTypeById(id: Long): PaymentType? = null
    }

    private val invoices = FakeSalesInvoiceApiClient()

    private fun viewModel() =
        BillReceiptViewModel(
            billApiClient = StubBillApiClient(),
            salesInvoiceApiClient = invoices,
            paymentTypeDataProvider = StubPaymentTypeDataProvider(),
            voucherApiClient = StubVoucherApiClient(),
            settingManager = SettingManager(FakeKeyValueStore()),
        )

    private val request = SalesInvoiceDto.SettleAtTillRequestDto(paymentTypeId = 3L, amount = "50.00")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a successful settle exposes the returned open amount`() =
        runTest(testDispatcher) {
            invoices.settleResult = FakeSalesInvoiceApiClient.response(openAmount = "30.00")
            val vm = viewModel()

            vm.settleReceivable(invoiceId = 7L, request = request)
            advanceUntilIdle()

            assertEquals("30.00", vm.settledOpenAmount.value, "the reduced open amount reaches the operator")
            assertEquals(request, invoices.lastRequest, "the request is passed through unchanged")
            assertNull(vm.error.value)
        }

    @Test
    fun `a refused settle surfaces the error and leaves the open amount null`() =
        runTest(testDispatcher) {
            invoices.settleThrows =
                PosApiException(
                    code = PosErrorCode.SALES_INVOICE_OVERPAYMENT,
                    httpStatus = 409,
                    message = "Amount 100.00 exceeds the open receivable of 80.00",
                    traceId = null,
                )
            val vm = viewModel()

            vm.settleReceivable(invoiceId = 7L, request = request)
            advanceUntilIdle()

            assertNotNull(vm.error.value, "the refusal must reach the operator")
            assertNull(vm.settledOpenAmount.value, "a receivable the server did not touch must not read as settled")
        }
}
