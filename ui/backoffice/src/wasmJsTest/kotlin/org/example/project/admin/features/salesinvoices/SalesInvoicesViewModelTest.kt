package org.example.project.admin.features.salesinvoices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceBucket
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesViewModel
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private fun issuedRow(id: Long) =
    SalesInvoiceDto.ResponseDto(
        id = id,
        invoiceNumber = "RG-2027-0000$id",
        status = "ISSUED",
        revenueOrigin = "STANDALONE",
        invoiceTypeCode = "380",
        precedingInvoiceId = null,
        billId = null,
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
        issueDate = "2026-08-16T10:00",
        serviceDate = "2026-08-16T10:00",
        dueDate = "2026-08-30T00:00",
        currency = "EUR",
        lineNetTotal = null,
        taxTotal = null,
        taxInclusiveTotal = "119.0000",
        prepaidAmount = null,
        amountDue = "119.0000",
        openAmount = "119.0000",
        notes = null,
        paymentTermsText = null,
        revenueAccountNo = null,
        buCode = null,
        documentId = 42L,
        issuedAt = "2026-08-16T10:05",
        cancelledAt = null,
        lines = emptyList(),
        vatBreakdown = emptyList(),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class SalesInvoicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the open bucket asks the server for open issued invoices`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += issuedRow(1)
            val vm = SalesInvoicesViewModel(api)

            vm.selectBucket(SalesInvoiceBucket.OPEN)
            advanceUntilIdle()

            assertEquals("ISSUED", api.lastStatus)
            assertEquals("380", api.lastType)
            assertEquals(true, api.lastOpen, "Offen must reach the request, or the bucket shows all issued rows")
            assertEquals(false, api.lastOverdue)
            assertEquals(1, vm.state.items.size)
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    // FO-65: the Gutschrift reason is mandatory and at most 60 characters; the server refuses too.
    @Test
    fun `cancelling needs a reason of at most 60 characters`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += issuedRow(1)
            val vm = SalesInvoicesViewModel(api)
            vm.select(1)
            advanceUntilIdle()

            vm.cancelSelected("")
            vm.cancelSelected("   ")
            vm.cancelSelected("x".repeat(61))
            advanceUntilIdle()
            assertEquals(emptyList(), api.cancelRequests)

            vm.cancelSelected("  " + "y".repeat(60) + " ")
            advanceUntilIdle()
            assertEquals(listOf(1L to SalesInvoiceDto.CancelRequestDto(reason = "y".repeat(60))), api.cancelRequests)
        }

    @Test
    fun `the reason gate is blank-proof and trimmed-length based`() {
        assertFalse(SalesInvoiceDto.CancelRequestDto.isValidReason(null))
        assertFalse(SalesInvoiceDto.CancelRequestDto.isValidReason("  "))
        assertFalse(SalesInvoiceDto.CancelRequestDto.isValidReason("x".repeat(61)))
        kotlin.test.assertTrue(SalesInvoiceDto.CancelRequestDto.isValidReason(" " + "x".repeat(60) + " "))
    }

    @Test
    fun `the overdue bucket adds the due-date filter on top of open`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val vm = SalesInvoicesViewModel(api)

            vm.selectBucket(SalesInvoiceBucket.OVERDUE)
            advanceUntilIdle()

            assertEquals("ISSUED", api.lastStatus)
            assertEquals("380", api.lastType)
            assertEquals(true, api.lastOpen)
            assertEquals(true, api.lastOverdue)
        }

    @Test
    fun `switching buckets drops the selection so the detail panel cannot disagree with the list`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.invoices += issuedRow(1)
            val vm = SalesInvoicesViewModel(api)

            vm.selectBucket(SalesInvoiceBucket.ISSUED)
            advanceUntilIdle()
            vm.select(1)
            advanceUntilIdle()
            assertEquals(1L, vm.state.selected?.id)

            vm.selectBucket(SalesInvoiceBucket.OPEN)
            advanceUntilIdle()

            assertNull(vm.state.selected)
        }

    @Test
    fun `selecting an invoice loads its lines and VAT breakdown for the detail panel`() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            val line =
                SalesInvoiceDto.LineDto(
                    lineNo = 1,
                    itemName = "Schnitzel",
                    itemDescription = null,
                    quantity = "2.000",
                    grossUnitPrice = "11.90",
                    netUnitPrice = "10.00",
                    netAmount = "20.00",
                    vatAmount = "3.80",
                    grossAmount = "23.80",
                    vatRate = "19.00",
                )
            val vat = SalesInvoiceDto.VatRowDto(vatRate = "19.00", vatCategoryCode = "S", taxableAmount = "20.00", vatAmount = "3.80")
            api.invoices += issuedRow(7).copy(lines = listOf(line), vatBreakdown = listOf(vat))
            val vm = SalesInvoicesViewModel(api)

            vm.select(7)
            advanceUntilIdle()

            val selected = vm.state.selected
            assertEquals(listOf(line), selected?.lines)
            assertEquals(listOf(vat), selected?.vatBreakdown)
            assertFalse(vm.state.isLoading)
        }
}
