package org.example.project.features.dsfinvk

import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins the DSFinV-K `datapayment.csv` (Bonkopf_Zahlarten) tender lines for the STORNO path.
 *
 * A storno reverse bill (`deletedBill != null`) carries no payment rows of its own — the
 * refunded tender lives on the source payments (`refundedBillId` -> reverse bill). DSFinV-K
 * Tz. 4.2.x represents a Belegstorno via `BON_STORNO=1` + inverted amounts, so the reverse
 * Beleg must carry the NEGATED tender lines. Previously the exporter queried the reverse
 * bill's own (empty) rows and emitted nothing, leaving a fully-reversed sale with a positive
 * card tender and no offsetting line — the payment-vs-revenue mismatch a Kassennachschau probes.
 */
@DisplayName("DSFinV-K datapayment.csv — storno tender lines")
class DsfinvkDataPaymentsStornoTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `reverse bill emits negated tender, original stays positive`() =
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("DPS1")
            val card = TestData.createPaymentType(name = "Card", isCash = false)
            val now = currentTimeStamp()

            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = TableSession.findById(tws.session.id)!!
                    this.billNumber = "DPS-ORIG-1"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = TableSession.findById(tws.session.id)!!
                    this.billNumber = "DPS-REV-1"
                    this.billAmount = BigDecimal("-30.00")
                    this.paidAmount = BigDecimal("-30.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = originalBill
                this.paymentType = card
                this.amount = BigDecimal("30.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundedBillId = reverseBill
            }

            val rows =
                service.generateDataPayments(
                    bills = listOf(originalBill, reverseBill),
                    zBons = emptyList<ZBon>(),
                    cashRegisterId = "SN-1",
                )

            // BON_ID is namespaced per source (audit finding C5): bills use the "B-" prefix.
            val originalRows = rows.filter { it.bonId == "B-${originalBill.id.value}" }
            val reverseRows = rows.filter { it.bonId == "B-${reverseBill.id.value}" }

            assertEquals(1, originalRows.size, "original sale Beleg should emit exactly one tender line")
            // ZAHLWAEH_* stay empty for base-currency EUR tenders (Anhang E p. 91, BKOPF-8).
            assertEquals(null, originalRows.single().zahlwaehBetrag)
            assertEquals("30.00", originalRows.single().basiswaehBetrag)

            assertEquals(1, reverseRows.size, "storno Beleg must emit exactly one negating tender line")
            assertEquals(null, reverseRows.single().zahlwaehBetrag)
            assertEquals("-30.00", reverseRows.single().basiswaehBetrag)
            assertEquals("Card", reverseRows.single().zahlartName)
        }

    @Test
    fun `non-storno bill still emits positive tender`() =
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("DPS2")
            val cash = TestData.createPaymentType(name = "Cash", isCash = true)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = TableSession.findById(tws.session.id)!!
                    this.billNumber = "DPS-PLAIN-1"
                    this.billAmount = BigDecimal("12.50")
                    this.paidAmount = BigDecimal("12.50")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("12.50")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            val rows =
                service.generateDataPayments(
                    bills = listOf(bill),
                    zBons = emptyList<ZBon>(),
                    cashRegisterId = "SN-1",
                )

            assertEquals(1, rows.size)
            assertEquals("12.50", rows.single().basiswaehBetrag)
        }
}
