package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.Payment
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Ausgangsrechnung covers the meal, never the tip. A tip booked "auf Rechnung" is money the guest
 * owes that the invoice cannot ask for, so the invoice is refused with 409
 * `SALES_INVOICE_TIP_ON_ACCOUNT` — on create and on issue — rather than silently capping the open
 * amount at the meal (which `ReceivableSettlement.split` used to do). The operator rebooks the tip to
 * cash or card first.
 */
@DisplayName("Sales invoice refused when the tip was paid on account")
class SalesInvoiceTipOnAccountTest {
    private val fixture = RebookFixture("si-tip-on-account-device")

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

    private fun issuer(): UUID = transaction { TestData.createAdmin(employeeNumber = "STA_I", username = "sta_issuer").id.value }

    private fun invoicesFor(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    private fun assertCreateRefused(billId: Long) {
        val error = assertFailsWith<PosException> { runBlocking { service().createFromBill(billId, buyer, createdBy = null) } }
        assertEquals(PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT, error.code, error.message)
        assertEquals(HttpStatusCode.Conflict, error.httpStatus)
        assertEquals(0L, invoicesFor(billId), "a refusal leaves no draft behind")
    }

    private fun invoiceAndIssue(billId: Long): Long {
        val draftId = runBlocking { service().createFromBill(billId, buyer, createdBy = null) }.let { transaction { it.id.value } }
        runBlocking { service().issue(draftId, issuer()) }
        return draftId
    }

    /** A Zahlartwechsel of a 23.00 card sale to [payments] with a 2.00 tip — the only way to split a tender. */
    private fun rebookedWith(
        recipient: TipRecipient,
        vararg payments: Pair<String, (RebookFixture.Sale) -> Long>,
    ): Long {
        val sale = fixture.closeSale(recipient)
        return fixture
            .rebook(
                sale,
                BillDto.RebookRequestDto(
                    payments = payments.map { (amount, type) -> PaymentDto.PaymentRequest(amount = amount, paymentTypeId = type(sale)) },
                    tips = "2.00",
                ),
            ).billId
    }

    @Test
    fun `an owner tip paid on account is refused`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(SaleSpec(recipient = TipRecipient.OWNER, tender = Tender.RECEIVABLE, originalTip = "2.00"))
                assertCreateRefused(sale.billId)
            }
        }

    @Test
    fun `an employee tip paid on account is refused`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(SaleSpec(recipient = TipRecipient.EMPLOYEE, tender = Tender.RECEIVABLE, originalTip = "2.00"))
                assertCreateRefused(sale.billId)
            }
        }

    @Test
    fun `the meal on account and the tip in cash is invoiced with the meal open`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val billId = rebookedWith(TipRecipient.OWNER, "23.00" to { it.receivableTypeId }, "2.00" to { it.cashTypeId })

                val id = invoiceAndIssue(billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertEquals(RevenueOrigin.POS_RECEIVABLE, invoice.revenueOrigin)
                    assertMoney("23.00", invoice.taxInclusiveTotal, "the meal")
                    assertMoney("23.00", invoice.openAmount, "the receivable is the meal")
                    assertMoney("0.00", invoice.prepaidAmount, "nothing of the meal was prepaid")
                }
            }
        }

    @Test
    fun `a mixed tender whose receivable is within the meal is invoiced`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val billId = rebookedWith(TipRecipient.EMPLOYEE, "10.00" to { it.receivableTypeId }, "15.00" to { it.cardTypeId })

                val id = invoiceAndIssue(billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertMoney("23.00", invoice.taxInclusiveTotal, "the meal")
                    assertMoney("10.00", invoice.openAmount, "only the receivable is open")
                    assertMoney("13.00", invoice.prepaidAmount, "the card leg paid the rest of the meal")
                }
            }
        }

    @Test
    fun `issue re-asserts it - a draft whose tip went on account afterwards is refused before a number is taken`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val billId = rebookedWith(TipRecipient.OWNER, "23.00" to { it.receivableTypeId }, "2.00" to { it.cashTypeId })
                val draftId = runBlocking { service().createFromBill(billId, buyer, null) }.let { transaction { it.id.value } }
                // Stands in for any path that could move the cash leg onto the account after the draft.
                transaction { Payment.find { PaymentTable.billId eq billId }.forEach { it.createsReceivable = true } }

                val error = assertFailsWith<PosException> { runBlocking { service().issue(draftId, issuer()) } }

                assertEquals(PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT, error.code)
                transaction { assertEquals(null, SalesInvoice.findById(draftId)!!.invoiceNumber, "no number consumed") }
            }
        }
}
