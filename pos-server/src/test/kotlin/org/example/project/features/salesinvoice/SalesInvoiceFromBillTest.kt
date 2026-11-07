package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end-to-end path: a real bill with items, VAT details and a TSE transaction becomes a draft
 * invoice, then an issued one.
 */
class SalesInvoiceFromBillTest {
    /**
     * Built per test, not as a field: `testApp` starts Koin inside the test body, and the PDF
     * service is resolved from it.
     */
    private fun service() =
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

    private var cachedIssuer: java.util.UUID? = null

    /**
     * One employee per test, created on first use. JUnit builds a fresh instance per test method, so
     * the cache resets — and creating a second admin inside one test collides on the unique username.
     */
    private fun issuer(): java.util.UUID =
        cachedIssuer ?: transaction {
            TestData.createAdmin(employeeNumber = "ISS002", username = "issuer2").id.value
        }.also { cachedIssuer = it }

    private val buyer =
        InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun billId(): Long =
        transaction {
            Bill
                .all()
                .first()
                .id.value
        }

    @Test
    fun `a settled bill becomes a POS_SETTLED draft carrying the receipt reference`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()

            val draftId = runBlocking { service().createFromBill(id, buyer, createdBy = null) }.id.value

            transaction {
                val draft = SalesInvoice.findById(draftId)!!
                assertEquals(SalesInvoiceStatus.DRAFT, draft.status)
                assertEquals(RevenueOrigin.POS_SETTLED, draft.revenueOrigin)
                assertEquals(id, draft.billId?.value)
                assertEquals("Musterfirma GmbH", draft.buyerName)
                assertTrue(draft.notes?.contains("Beleg") == true, "the receipt reference belongs on the invoice")
                assertEquals(2, draft.lines.toList().size)
                assertEquals(1, draft.vatBreakdown.toList().size)
            }
        }
    }

    @Test
    fun `the draft's totals equal the bill's fiscal split`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()
            val expectedTotal =
                transaction {
                    Bill
                        .all()
                        .first()
                        .vatDetails
                        .fold(BigDecimal.ZERO) { a, r -> a + r.totalAmount }
                }

            val draftId = runBlocking { service().createFromBill(id, buyer, null) }.id.value

            transaction {
                assertEquals(0, expectedTotal.compareTo(SalesInvoice.findById(draftId)!!.taxInclusiveTotal))
            }
        }
    }

    @Test
    fun `the TSE block is snapshotted onto the invoice`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()
            val expected =
                transaction {
                    Bill
                        .all()
                        .first()
                        .tseTransaction
                        ?.transactionNumber
                }

            val draftId = runBlocking { service().createFromBill(id, buyer, null) }.id.value

            transaction {
                val draft = SalesInvoice.findById(draftId)!!
                assertEquals(expected, draft.tseTransactionNumber, "§6 KassenSichV: the Beleg discloses the signature")
                assertNotNull(draft.tseSerialNumber)
                assertNotNull(draft.tseSignature)
            }
        }
    }

    @Test
    fun `line VAT rates land in percent units, not as the stored fraction`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val draftId = runBlocking { service().createFromBill(billId(), buyer, null) }.id.value

            transaction {
                val draft = SalesInvoice.findById(draftId)!!
                assertEquals(0, BigDecimal("19.00").compareTo(draft.lines.first().vatRate))
                assertEquals(0, BigDecimal("19.00").compareTo(draft.vatBreakdown.first().vatRate))
            }
        }
    }

    @Test
    fun `a second invoice for the same bill is refused`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val id = billId()
            runBlocking { service().createFromBill(id, buyer, null) }

            val error = assertFailsWith<PosException> { runBlocking { service().createFromBill(id, buyer, null) } }
            assertEquals(PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED, error.code)
        }
    }

    @Test
    fun `a training bill is refused`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing(isTraining = true) }) {
            val error =
                assertFailsWith<PosException> { runBlocking { service().createFromBill(billId(), buyer, null) } }
            assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        }
    }

    @Test
    fun `an unknown bill is refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val error =
                assertFailsWith<PosException> { runBlocking { service().createFromBill(999_999L, buyer, null) } }
            assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        }
    }

    @Test
    fun `the draft issues cleanly and comes out already paid`() {
        testApp(setupData = { TestData.createSettledBillForInvoicing() }) {
            val draftId = runBlocking { service().createFromBill(billId(), buyer, null) }.id.value

            runBlocking { service().issue(draftId, issuer()) }

            transaction {
                val issued = SalesInvoice.findById(draftId)!!
                assertEquals(SalesInvoiceStatus.ISSUED, issued.status)
                assertNotNull(issued.invoiceNumber)
                assertEquals(0, BigDecimal.ZERO.compareTo(issued.openAmount), "the till already took the money")
                assertEquals(0, issued.taxInclusiveTotal!!.compareTo(issued.prepaidAmount))
            }
        }
    }
}
