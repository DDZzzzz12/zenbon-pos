package org.example.project.features.salesinvoice

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
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
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An OWNER tip is taxable revenue on the Kassenbeleg — the bill folds it into its item VAT rows — but
 * it is never part of the Ausgangsrechnung, which covers the meal itself. Before this, such a bill
 * could not be invoiced at all ("the bill total disagrees with its own VAT details").
 *
 * Every bill goes through the REAL bill service ([RebookFixture]) under TseMode.SIMULATION, so the VAT
 * rows are the ones `BillServiceImpl` signed, and the invoice has to take back out exactly the share
 * `OwnerTipApportionment` put in.
 */
@DisplayName("Sales invoice from a bill with an owner tip")
class SalesInvoiceOwnerTipTest {
    private val fixture = RebookFixture("si-owner-tip-device")

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

    private fun issuer(): UUID = transaction { TestData.createAdmin(employeeNumber = "SOT_I", username = "sot_issuer").id.value }

    private fun invoicesFor(billId: Long): Long = transaction { SalesInvoice.find { SalesInvoiceTable.billId eq billId }.count() }

    /** Creates and issues the invoice for [billId]; returns its id. */
    private fun invoiceAndIssue(billId: Long): Long {
        val draftId = runBlocking { service().createFromBill(billId, buyer, createdBy = null) }.let { transaction { it.id.value } }
        runBlocking { service().issue(draftId, issuer()) }
        return draftId
    }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual?.toPlainString()}")

    private fun rate(percent: String) = BigDecimal(percent).setScale(2)

    private fun isPercent(
        fraction: BigDecimal,
        percent: String,
    ) = fraction.movePointRight(2).compareTo(BigDecimal(percent)) == 0

    /** The bill's own VAT row total at [percent] (percent units), as `BillServiceImpl` stored it. */
    private fun billRowTotal(
        billId: Long,
        percent: String,
    ): BigDecimal =
        transaction {
            Bill
                .findById(billId)!!
                .vatDetails
                .single { row -> isPercent(row.vat.vatRate, percent) }
                .totalAmount
        }

    /**
     * Asserts the issued invoice is the meal: the VAT row at [percent] carries [mealGross], net by
     * `gross / (1 + rate)` HALF_UP, VAT by subtraction; and the §14 totals are internally consistent.
     */
    private fun assertMealRow(
        invoice: SalesInvoice,
        percent: String,
        mealGross: String,
    ) {
        val gross = BigDecimal(mealGross)
        val net = gross.divide(BigDecimal.ONE + BigDecimal(percent).movePointLeft(2), 2, RoundingMode.HALF_UP)
        val row = invoice.vatBreakdown.single { it.vatRate.compareTo(rate(percent)) == 0 }
        assertMoney(net.toPlainString(), row.taxableAmount, "$percent % net")
        assertMoney((gross - net).toPlainString(), row.vatAmount, "$percent % VAT by subtraction")
    }

    private fun assertTotalsConsistent(invoice: SalesInvoice) {
        val rows = invoice.vatBreakdown.toList()
        val net = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.taxableAmount }
        val vat = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.vatAmount }
        assertMoney(net.toPlainString(), invoice.taxExclusiveTotal, "BT-109 = Σ BT-116")
        assertMoney(vat.toPlainString(), invoice.taxTotal, "BT-110 = Σ BT-117")
        assertMoney((net + vat).toPlainString(), invoice.taxInclusiveTotal, "BT-112 = BT-109 + BT-110")
        val lineGross = invoice.lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineGrossAmount }
        assertMoney(lineGross.toPlainString(), invoice.taxInclusiveTotal, "the lines are the whole invoice")
    }

    @Test
    fun `an owner tip at 19 percent is left off - the invoice is the meal`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
                assertMoney("25.00", billRowTotal(sale.billId, "19"), "precondition: the bill folded the tip into its 19 % row")

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertMoney("23.00", invoice.taxInclusiveTotal, "invoice gross = the meal, never the tip")
                    assertMealRow(invoice, "19", "23.00")
                    assertTotalsConsistent(invoice)
                    assertMoney("23.00", invoice.prepaidAmount, "a card sale is prepaid in full - the meal")
                }
            }
        }

    @Test
    fun `an owner tip over 19 and 7 percent comes out of each row by the shared split`() =
        testApp { _ ->
            fixture.withSimulationTse {
                // 10.00 @ 19 %, 5.00 @ 7 %, owner tip 2.00: split 1.33 / 0.67 by the post-promotion gross.
                val sale =
                    fixture.closeSale(
                        SaleSpec(
                            recipient = TipRecipient.OWNER,
                            items = listOf("10.00" to 0, "5.00" to 1),
                            originalTip = "2.00",
                        ),
                    )
                assertMoney("11.33", billRowTotal(sale.billId, "19"), "precondition: 19 % row = 10.00 + 1.33")
                assertMoney("5.67", billRowTotal(sale.billId, "7"), "precondition: 7 % row = 5.00 + 0.67")

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertMoney("15.00", invoice.taxInclusiveTotal, "the meal")
                    assertMealRow(invoice, "19", "10.00")
                    assertMealRow(invoice, "7", "5.00")
                    assertTotalsConsistent(invoice)
                }
            }
        }

    @Test
    fun `an employee tip bill is unchanged - it never had a VAT row`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.EMPLOYEE, originalTip = "2.00")

                val id = invoiceAndIssue(sale.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    val billRow = Bill.findById(sale.billId)!!.vatDetails.single()
                    val row = invoice.vatBreakdown.single()
                    assertMoney("23.00", invoice.taxInclusiveTotal, "the meal")
                    assertMoney(billRow.netAmount.toPlainString(), row.taxableAmount, "copied verbatim")
                    assertMoney(billRow.vatAmount.toPlainString(), row.vatAmount, "copied verbatim")
                    assertTotalsConsistent(invoice)
                }
            }
        }

    @Test
    fun `a Zahlartwechsel Neubuchung with an owner tip is invoiced without the tip`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.OWNER)
                val rebooked =
                    fixture.rebook(
                        sale,
                        BillDto.RebookRequestDto(
                            payments = listOf(PaymentDto.PaymentRequest(amount = "25.00", paymentTypeId = sale.cardTypeId)),
                            tips = "2.00",
                        ),
                    )
                assertMoney("25.00", billRowTotal(rebooked.billId, "19"), "precondition: the Neubuchung folded the tip in")

                val id = invoiceAndIssue(rebooked.billId)

                transaction {
                    val invoice = SalesInvoice.findById(id)!!
                    assertEquals(SalesInvoiceStatus.ISSUED, invoice.status)
                    assertMoney("23.00", invoice.taxInclusiveTotal, "the meal")
                    assertMealRow(invoice, "19", "23.00")
                    assertTotalsConsistent(invoice)
                }
            }
        }

    @Test
    fun `a tampered VAT row on an owner-tip bill is still refused, naming the rate that does not split`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
                transaction {
                    val row = Bill.findById(sale.billId)!!.vatDetails.single()
                    row.totalAmount = row.totalAmount + BigDecimal("0.01")
                }

                val error =
                    assertFailsWith<PosException> {
                        runBlocking { service().createFromBill(sale.billId, buyer, createdBy = null) }
                    }

                assertEquals(PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE, error.code)
                assertTrue(error.message!!.contains("at 19 %"), error.message)
                assertTrue(error.message!!.contains("owner tip of 2.00"), error.message)
                assertEquals(0L, invoicesFor(sale.billId), "a refusal leaves no draft behind")
            }
        }

    @Test
    fun `issue re-asserts it - an owner-tip bill tampered after the draft is refused, naming the tip`() =
        testApp { _ ->
            fixture.withSimulationTse {
                val sale = fixture.closeSale(TipRecipient.OWNER, originalTip = "2.00")
                val draftId = runBlocking { service().createFromBill(sale.billId, buyer, null) }.let { transaction { it.id.value } }
                transaction {
                    val row = Bill.findById(sale.billId)!!.vatDetails.single()
                    row.totalAmount = row.totalAmount + BigDecimal("0.01")
                }

                val error = assertFailsWith<PosException> { runBlocking { service().issue(draftId, issuer()) } }

                assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
                assertTrue(error.message!!.contains("less the owner tip 2.00"), error.message)
                transaction { assertEquals(SalesInvoiceStatus.DRAFT, SalesInvoice.findById(draftId)!!.status) }
            }
        }
}
