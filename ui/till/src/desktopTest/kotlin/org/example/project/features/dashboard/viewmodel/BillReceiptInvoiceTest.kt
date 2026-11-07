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
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.features.dashboard.presentation.viewmodel.BillReceiptViewModel
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The till's invoice action is three server calls in a fixed order — create the draft, issue it,
 * then read the archived PDF — and each one is a distinct legal step. These pin that order, and pin
 * that a refused creation shows no PDF: a document the server never issued must not appear on
 * screen as though it had been.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillReceiptInvoiceTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Records the call order so the test can assert create -> issue -> pdf, not just the outcome. */
    private class FakeSalesInvoiceApiClient : SalesInvoiceApiClient {
        val calls = mutableListOf<String>()
        var createThrows: Throwable? = null
        var issueThrows: Throwable? = null
        var createResult: SalesInvoiceDto.ResponseDto = response(id = 7L, number = null, status = "DRAFT")
        var issueResult: SalesInvoiceDto.ResponseDto = response(id = 7L, number = "RG-2026-00001", status = "ISSUED")
        var pdfResult: ByteArray = byteArrayOf(1, 2, 3)

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

        override suspend fun createStandalone(request: SalesInvoiceDto.CreateStandaloneRequestDto): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun updateStandalone(
            id: Long,
            request: SalesInvoiceDto.UpdateStandaloneRequestDto,
        ): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun deleteStandalone(id: Long) = TODO()

        override suspend fun createFromBill(request: SalesInvoiceDto.CreateFromBillRequestDto): SalesInvoiceDto.ResponseDto {
            calls += "create:${request.billId}:${request.buyerName}"
            createThrows?.let { throw it }
            return createResult
        }

        override suspend fun issue(id: Long): SalesInvoiceDto.ResponseDto {
            calls += "issue:$id"
            issueThrows?.let { throw it }
            return issueResult
        }

        override suspend fun settleAtTill(
            id: Long,
            request: SalesInvoiceDto.SettleAtTillRequestDto,
        ): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun cancel(
            id: Long,
            request: SalesInvoiceDto.CancelRequestDto,
        ): SalesInvoiceDto.ResponseDto = TODO()

        override suspend fun pdf(id: Long): ByteArray {
            calls += "pdf:$id"
            return pdfResult
        }

        override suspend fun datevExportStatistics(
            start: String,
            end: String,
        ): DatevArExportDto.ArExportStatisticsDto = TODO()

        override suspend fun datevExport(
            start: String,
            end: String,
        ): ByteArray = TODO()

        companion object {
            fun response(
                id: Long,
                number: String?,
                status: String,
            ) = SalesInvoiceDto.ResponseDto(
                id = id,
                invoiceNumber = number,
                status = status,
                revenueOrigin = "POS_SETTLED",
                invoiceTypeCode = "380",
                precedingInvoiceId = null,
                billId = 42L,
                debtorId = null,
                buyerName = "Musterfirma GmbH",
                buyerVatId = null,
                buyerStreet = null,
                buyerZip = null,
                buyerCity = null,
                buyerCountry = null,
                buyerEmail = null,
                sellerName = null,
                sellerVatId = null,
                sellerTaxNumber = null,
                issueDate = "2026-08-16T12:00",
                serviceDate = "2026-08-16T12:00",
                dueDate = null,
                currency = "EUR",
                lineNetTotal = "23.78",
                taxTotal = "4.52",
                taxInclusiveTotal = "28.30",
                prepaidAmount = "28.30",
                amountDue = "0.00",
                openAmount = "0.00",
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

    private val request =
        SalesInvoiceDto.CreateFromBillRequestDto(
            billId = 42L,
            buyerName = "Musterfirma GmbH",
            buyerStreet = "Industriestr. 5",
            buyerZip = "10117",
            buyerCity = "Berlin",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the till creates, issues and then reads the archived PDF`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.createAndIssueInvoice(request)
            advanceUntilIdle()

            assertEquals(
                listOf("create:42:Musterfirma GmbH", "issue:7", "pdf:7"),
                invoices.calls,
                "each step is a distinct legal act and must run in this order",
            )
            assertEquals("RG-2026-00001", vm.issuedInvoiceNumber.value)
            assertContentEquals(byteArrayOf(1, 2, 3), vm.generatedPdfBytes.value)
            assertNull(vm.error.value)
        }

    // emu20 NOTE-q: the just-billed dialog kept offering "Rechnung erstellen" after issuing.
    @Test
    fun `an issued invoice is remembered for its bill, also after the PDF is closed`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.createAndIssueInvoice(request)
            advanceUntilIdle()
            vm.clearGeneratedPdf()

            assertEquals(mapOf(42L to 7L), vm.invoicedBills.value, "the dialog hides 'Rechnung erstellen' on it")
        }

    @Test
    fun `a refused invoice is not remembered and maps to its own message`() =
        runTest(testDispatcher) {
            invoices.createThrows =
                PosApiException(
                    code = PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED,
                    httpStatus = 409,
                    message = "Bill 42 already has a live invoice.",
                    traceId = null,
                )
            val vm = viewModel()

            vm.createAndIssueInvoice(request)
            advanceUntilIdle()

            assertEquals(emptyMap(), vm.invoicedBills.value)
            assertEquals(SalesInvoiceTillKeys.alreadyInvoicedMessage, vm.error.value?.messageKey, "not 'Unerwarteter Fehler'")
        }

    @Test
    fun `a bill the server refuses to invoice shows an error and no PDF`() =
        runTest(testDispatcher) {
            invoices.createThrows =
                PosApiException(
                    code = PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED,
                    httpStatus = 409,
                    message = "Bill 42 already has a live invoice.",
                    traceId = null,
                )
            val vm = viewModel()

            vm.createAndIssueInvoice(request)
            advanceUntilIdle()

            assertNotNull(vm.error.value, "the refusal must reach the operator")
            assertNull(vm.generatedPdfBytes.value, "no document was issued, so none may be shown")
            assertNull(vm.issuedInvoiceNumber.value)
            assertEquals(listOf("create:42:Musterfirma GmbH"), invoices.calls, "issuing must not be attempted")
        }

    @Test
    fun `showing an existing invoice reads the archived file only`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.showInvoice(invoiceId = 7L)
            advanceUntilIdle()

            assertEquals(listOf("pdf:7"), invoices.calls, "a reprint never re-renders and never re-issues")
            assertContentEquals(byteArrayOf(1, 2, 3), vm.generatedPdfBytes.value)
        }
}
