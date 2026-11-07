package org.example.project.features.salesinvoice

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.data.repository.SalesInvoiceRepositoryImpl
import org.example.project.features.salesinvoice.data.service.BillInvoiceFactsGatherer
import org.example.project.features.salesinvoice.data.service.SalesInvoiceServiceImpl
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.InvoiceBuyer
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * An Ausgangsrechnung renders a STANDING SALE — the original of a sale that was not stornoed. A
 * Storno bill, a cancelled original and a receivable clearing are refused with 409
 * `BILL_NOT_A_STANDING_SALE`, on create and again on issue.
 *
 * Every bill goes through the REAL bill service (`createBill` → `deleteBill`, [RebookFixture]) under
 * TseMode.SIMULATION.
 */
@DisplayName("Sales invoice only from a standing sale")
class SalesInvoiceStandingSaleTest {
    private val fixture = RebookFixture("si-standing-sale-device")

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

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

    private val buyer = InvoiceBuyer(name = "Musterfirma GmbH", street = "Industriestr. 5", zip = "10117", city = "Berlin")

    private fun issuer(): UUID = transaction { TestData.createAdmin(employeeNumber = "SSI_I", username = "ssi_issuer").id.value }

    private fun stornoOf(billId: Long): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq billId }
                .single()
                .id.value
        }

    private fun invoicesFor(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    /** Creating from [billId] is refused with 409 BILL_NOT_A_STANDING_SALE and writes no invoice. */
    private fun assertCreateRefused(
        billId: Long,
        what: String,
    ) {
        val error = assertFailsWith<PosException>(what) { runBlocking { service().createFromBill(billId, buyer, createdBy = null) } }
        assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE, error.code, what)
        assertEquals(HttpStatusCode.Conflict, error.httpStatus, what)
        assertEquals(0L, invoicesFor(billId), "$what: a refusal leaves no draft behind")
    }

    /**
     * A clearing is a payment receipt against an issued invoice. Built by hand: the real till path
     * ([SalesInvoiceServiceImpl.settleAtTill]) needs a receivable invoice, and all that matters here
     * is the bill's `clears_sales_invoice_id`.
     */
    private fun handBuiltClearingOf(
        originalBillId: Long,
        invoiceId: Long,
    ): Long =
        transaction {
            val original = Bill.findById(originalBillId)!!
            Bill
                .new {
                    this.employeeId = original.employeeId
                    this.tableSession = original.tableSession
                    this.billNumber = "RE-CLEAR-1"
                    this.billAmount = BigDecimal.ZERO
                    this.paidAmount = BigDecimal("23.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.isTraining = false
                    this.clearsSalesInvoiceId = SalesInvoice.findById(invoiceId)?.id?.value
                }.id.value
        }

    // ------------------------------------------------------------------------------------------
    // Refused kinds
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a Storno bill and the cancelled original cannot be invoiced`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                fixture.storno(sale)

                assertCreateRefused(stornoOf(sale.billId), "Storno bill")
                assertCreateRefused(sale.billId, "cancelled original")
            }
        }

    @Test
    fun `a receivable clearing cannot be invoiced`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                val invoiceId = runBlocking { service().createFromBill(sale.billId, buyer, null) }.let { transaction { it.id.value } }
                runBlocking { service().issue(invoiceId, issuer()) }

                assertCreateRefused(handBuiltClearingOf(sale.billId, invoiceId), "receivable clearing")
            }
        }

    @Test
    fun `issue re-asserts it - a draft whose bill is no longer a standing sale is refused before a number is taken`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                val draftId = runBlocking { service().createFromBill(sale.billId, buyer, null) }.let { transaction { it.id.value } }
                val other = fixture.closeSale(TipRecipient.EMPLOYEE)
                fixture.storno(other)
                val stornoId = stornoOf(other.billId)
                // No API repoints a draft; this stands in for any future path that could.
                transaction { SalesInvoice.findById(draftId)!!.billId = EntityID(stornoId, BillTable) }

                val error = assertFailsWith<PosException> { runBlocking { service().issue(draftId, issuer()) } }

                assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE, error.code)
                transaction {
                    val draft = SalesInvoice.findById(draftId)!!
                    assertEquals(SalesInvoiceStatus.DRAFT, draft.status)
                    assertNull(draft.invoiceNumber, "a refused issue consumes no number")
                }
            }
        }

    @Test
    fun `POST from-bill for a Storno bill is a 409 BILL_NOT_A_STANDING_SALE over HTTP`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
                fixture.storno(sale)
                val stornoId = stornoOf(sale.billId)
                val token = runBlocking { TestAuthHelper.loginAsAdmin(client) }

                val response =
                    runBlocking {
                        client.post("/api/v1/sales-invoices/from-bill") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                SalesInvoiceDto.CreateFromBillRequestDto(
                                    billId = stornoId,
                                    buyerName = "Musterfirma GmbH",
                                    buyerStreet = "Industriestr. 5",
                                    buyerZip = "10117",
                                    buyerCity = "Berlin",
                                ),
                            )
                        }
                    }

                assertEquals(HttpStatusCode.Conflict, response.status)
                val problem = testJson.decodeFromString<ProblemDetail>(runBlocking { response.bodyAsText() })
                assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE.name, problem.code)
                assertEquals(0L, invoicesFor(stornoId))
            }
        }

    // ------------------------------------------------------------------------------------------
    // The original stays invoiceable
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the original of a sale is invoiceable`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

                val draftId = runBlocking { service().createFromBill(sale.billId, buyer, null) }.let { transaction { it.id.value } }
                runBlocking { service().issue(draftId, issuer()) }

                transaction {
                    val issued = SalesInvoice.findById(draftId)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, issued.status)
                    assertMoney("23.00", issued.taxInclusiveTotal, "invoice total")
                }
            }
        }
}
