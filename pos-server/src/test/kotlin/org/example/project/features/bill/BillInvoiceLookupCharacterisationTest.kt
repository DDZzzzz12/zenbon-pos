package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the bill side reads from a bill's sales invoice, through the public [BillService] only,
 * so the same assertions hold whichever way the lookup is wired: no / DRAFT / ISSUED / CANCELLED
 * invoice against `isRebookable`, `isDeletable` and the `salesInvoice*` wire fields; the Storno
 * deleting a DRAFT and refusing an ISSUED invoice; and the clears-invoice relation link.
 */
@DisplayName("Bill / sales-invoice lookups (characterisation)")
class BillInvoiceLookupCharacterisationTest {
    private val deviceId = "inv-lookup-device"
    private val fixture = RebookFixture(deviceId)
    private val buyer = InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    @AfterEach
    fun tearDown() {
        BillNumberTracker.clearAll()
    }

    private fun billService(): BillService = GlobalContext.get().get()

    private fun invoiceService() =
        SalesInvoiceServiceImpl(
            repository = SalesInvoiceRepositoryImpl(),
            numbers = ArNumberSequenceRepositoryImpl(),
            facts = BillInvoiceFactsGatherer(),
            pdf = GlobalContext.get().get(),
            billRepository = GlobalContext.get().get(),
            terminalRepository = GlobalContext.get().get(),
            saleSigning = GlobalContext.get().get(),
            configManager = GlobalContext.get().get(),
            allocationWriter = GlobalContext.get().get(),
        )

    private var seq = 0

    private fun issuer(): java.util.UUID {
        val n = ++seq
        return transaction { TestData.createAdmin(employeeNumber = "LK_I$n", username = "lk_issuer$n").id.value }
    }

    private fun draftFor(billId: Long): Long =
        runBlocking { invoiceService().createFromBill(billId, buyer, createdBy = null) }.let { transaction { it.id.value } }

    private fun issue(invoiceId: Long) {
        runBlocking { invoiceService().issue(invoiceId, issuer()) }
    }

    private fun cancel(invoiceId: Long) {
        runBlocking { invoiceService().cancel(invoiceId, cancelledBy = issuer(), reason = "Tippfehler") }
    }

    private fun wire(billId: Long): BillDto.BillResponseDto = runBlocking { billService().getBillById(billId.toString()) }

    private fun invoiceCount(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    private fun assertNoInvoiceFields(dto: BillDto.BillResponseDto) {
        assertNull(dto.salesInvoiceId)
        assertNull(dto.salesInvoiceNumber)
        assertNull(dto.salesInvoiceOpenAmount)
    }

    @Test
    fun `no invoice - rebookable and deletable, no invoice fields`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val dto = wire(sale.billId)
            assertTrue(dto.isRebookable)
            assertTrue(dto.isDeletable)
            assertNoInvoiceFields(dto)
        }

    @Test
    fun `DRAFT invoice - still rebookable and deletable, id reported, no number`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftFor(sale.billId)
            val dto = wire(sale.billId)
            assertTrue(dto.isRebookable)
            assertTrue(dto.isDeletable)
            assertEquals(invoiceId, dto.salesInvoiceId)
            assertNull(dto.salesInvoiceNumber, "a draft consumed no number")
            val open = transaction { SalesInvoice.findById(invoiceId)!!.openAmount }
            assertEquals(
                open?.let {
                    org.example.project.utils.formatter
                        .formatDecimal(it)
                },
                dto.salesInvoiceOpenAmount,
            )
        }

    @Test
    fun `ISSUED invoice - neither rebookable nor deletable, number and open amount reported`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftFor(sale.billId)
            issue(invoiceId)
            val (number, open) = transaction { SalesInvoice.findById(invoiceId)!!.let { it.invoiceNumber to it.openAmount } }
            val dto = wire(sale.billId)
            assertFalse(dto.isRebookable)
            assertFalse(dto.isDeletable)
            assertEquals(invoiceId, dto.salesInvoiceId)
            assertNotNull(number)
            assertEquals(number, dto.salesInvoiceNumber)
            assertNotNull(open, "an issued invoice carries an open amount")
            assertEquals(0, open.compareTo(BigDecimal(dto.salesInvoiceOpenAmount!!)))
            assertEquals(
                org.example.project.utils.formatter
                    .formatDecimal(open),
                dto.salesInvoiceOpenAmount,
            )
        }

    @Test
    fun `CANCELLED invoice - the bill's slot is free again`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftFor(sale.billId)
            issue(invoiceId)
            cancel(invoiceId)
            val dto = wire(sale.billId)
            assertTrue(dto.isRebookable)
            assertTrue(dto.isDeletable)
            assertNoInvoiceFields(dto)
        }

    @Test
    fun `storno deletes a DRAFT invoice with the bill`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            draftFor(sale.billId)
            assertEquals(1L, invoiceCount(sale.billId))
            fixture.storno(sale)
            assertEquals(0L, invoiceCount(sale.billId), "the draft goes with the Storno")
            assertNotNull(transaction { Bill.findById(sale.billId)!!.deletingReason })
        }

    @Test
    fun `storno refuses an ISSUED invoice with the same code and message`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftFor(sale.billId)
            issue(invoiceId)
            val (number, billNumber) = transaction { SalesInvoice.findById(invoiceId)!!.invoiceNumber to Bill.findById(sale.billId)!!.billNumber }
            val error = assertFailsWith<PosException> { fixture.storno(sale) }
            assertEquals(PosErrorCode.SALES_INVOICE_IMMUTABLE, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
            assertEquals(
                "Cannot delete bill $billNumber: invoice $number was issued for it. " +
                    "Cancel that invoice first — an issued invoice is only left through a 381 Gutschrift.",
                error.message,
            )
            assertEquals(1L, invoiceCount(sale.billId), "the issued invoice is untouched")
            assertEquals(null, transaction { Bill.findById(sale.billId)!!.deletingReason })
        }

    @Test
    fun `a bill that clears an invoice links back to the bill behind that invoice`() =
        testApp { _ ->
            val invoiced = fixture.closeSale(TipRecipient.EMPLOYEE)
            val invoiceId = draftFor(invoiced.billId)
            val clearing = fixture.closeSale(TipRecipient.EMPLOYEE)
            transaction { Bill.findById(clearing.billId)!!.clearsSalesInvoiceId = invoiceId }

            val invoicedNumber = transaction { Bill.findById(invoiced.billId)!!.billNumber }
            assertEquals(BillDto.BillLinkDto(invoiced.billId, invoicedNumber), wire(clearing.billId).relations.clearsBill)
            assertNull(wire(invoiced.billId).relations.clearsBill)
        }
}
