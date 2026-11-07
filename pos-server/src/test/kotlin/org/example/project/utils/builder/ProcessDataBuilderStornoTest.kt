package org.example.project.utils.builder

import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Characterization tests pinning the Kassenbeleg-V1 processData strings emitted for a
 * bill STORNO. The storno path (`BillServiceImpl.deleteBill`) signs the reversal as
 * `BonType.BELEG` with negated amounts (DSFinV-K Tz. 4.2.1/4.2.2 — `AVBelegstorno` cannot be
 * used with TSE) and passes the original bill's positive payment rows with `refundedBillId`
 * stamped, so the sign of every tender must come from that flag — NOT from the (positive)
 * amount. A Kassennachschau checks that a signed storno receipt represents a refund with the
 * correct (negative) sign.
 *
 * This differs from the payment-change (Zahlartwechsel) path, where the sign is carried
 * natively by negative payment amounts and `refundedBillId` is null — see
 * [ProcessDataBuilderPaymentChangeTest].
 */
@DisplayName("ProcessDataBuilder — storno processData")
class ProcessDataBuilderStornoTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Builds the storno processData for the given (isCash, currency, positiveAmount) tenders. */
    private fun buildStornoProcessData(tenders: List<Triple<Boolean, String, String>>): String =
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("PDBS1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            fun newBill(number: String): Bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = number
                    this.billAmount = BigDecimal.ZERO
                    this.paidAmount = BigDecimal.ZERO
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            val reverseBill = newBill("PDBS-REV-1")
            val originalBill = newBill("PDBS-ORIG-1")
            var typeIdx = 0
            val paymentRows =
                tenders.map { (isCash, currency, amount) ->
                    val type = TestData.createPaymentType(name = "PDBS-T${typeIdx++}", isCash = isCash)
                    Payment.new {
                        this.bill = originalBill
                        this.paymentType = type
                        this.amount = BigDecimal(amount)
                        this.currency = currency
                        this.paymentStatus = PaymentStatus.REFUNDED
                        this.paymentTime = now
                        this.refundedBillId = reverseBill
                    }
                }
            ProcessDataBuilder.build(
                processType = ProcessType.KASSENBELEG_V1,
                bonType = BonType.BELEG,
                vatDetail = emptyList(),
                payments = paymentRows,
            )
        }

    @Test
    fun `card (Unbar) storno signs the tender negative`() {
        val processData = buildStornoProcessData(listOf(Triple(false, "EUR", "30.00")))
        assertEquals("${BonType.BELEG}^0.00_0.00_0.00_0.00_0.00^-30.00:Unbar", processData)
    }

    @Test
    fun `mixed cash and card storno signs both tenders negative`() {
        val processData =
            buildStornoProcessData(
                listOf(Triple(true, "EUR", "20.00"), Triple(false, "EUR", "30.00")),
            )
        assertEquals(
            "${BonType.BELEG}^0.00_0.00_0.00_0.00_0.00^-20.00:Bar_-30.00:Unbar",
            processData,
        )
    }

    @Test
    fun `foreign-currency cash storno signs the tender negative`() {
        val processData = buildStornoProcessData(listOf(Triple(true, "USD", "30.00")))
        assertEquals("${BonType.BELEG}^0.00_0.00_0.00_0.00_0.00^-30.00:Bar:USD", processData)
    }

    @Test
    fun `foreign-currency card storno signs the tender negative`() {
        val processData = buildStornoProcessData(listOf(Triple(false, "USD", "30.00")))
        assertEquals("${BonType.BELEG}^0.00_0.00_0.00_0.00_0.00^-30.00:Unbar:USD", processData)
    }
}
