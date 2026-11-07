package org.example.project.features.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * QA emu14 NOTE-5: Statistik "Rabatte" showed 0.00 for a day whose Z-Bon reported Rabatte 25.00,
 * because `totalDiscounts` summed only `bill.discountAmount` (manual line discounts) and ignored
 * `bill.promotionAmount`, which carries every bill-wide and item promotion. The Z-Bon has always
 * summed both over its active bills; statistics now does the same.
 */
@DisplayName("Statistics — Rabatte include promotions, over active bills only")
class StatisticsDiscountsIncludePromotionsTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    @Test
    fun `totalDiscounts sums line discounts and promotions of active bills, a Zahlartwechsel once`() {
        testApp(
            setupData = {
                TestData.createAdmin()
                val tws = TestData.createTableWithSession("DISC1")
                val billTime = today.atTime(12, 0, 0)

                transaction {
                    val session = tws.session
                    val employee = session.employeeId

                    fun bill(
                        number: String,
                        amount: String,
                        discount: String = "0",
                        promotion: String = "0",
                    ) = Bill.new {
                        this.employeeId = employee
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal(amount)
                        this.paidAmount = BigDecimal(amount) - BigDecimal(promotion)
                        this.discountAmount = BigDecimal(discount)
                        this.promotionAmount = BigDecimal(promotion)
                        this.startTime = billTime
                        this.endTime = billTime
                        this.isTraining = false
                    }

                    // A manual line discount (2.00) and a bill-wide % promotion (3.00) on one bill.
                    bill("DISC-A", "30.0000", discount = "2.0000", promotion = "3.0000")
                    // Stacked item promotions (8.00) on another.
                    bill("DISC-B", "40.0000", promotion = "8.0000")

                    // An ordinary Storno: neither the original's nor the Storno's promotion counts.
                    val reversed = bill("DISC-C", "20.0000", promotion = "4.0000")
                    bill("DISC-C-ST", "-20.0000", promotion = "-4.0000").apply { deletedBill = reversed }
                    reversed.deletingReason = "wrong order"

                    // A Zahlartwechsel: original + Storno excluded, the Neubuchung (5.00) counts once.
                    val rebooked = bill("DISC-D", "25.0000", promotion = "5.0000")
                    bill("DISC-D-ST", "-25.0000", promotion = "-5.0000").apply {
                        deletedBill = rebooked
                        stornoReason = StornoReason.ZAHLARTWECHSEL
                    }
                    rebooked.deletingReason = "Zahlartwechsel"
                    bill("DISC-D-NEU", "25.0000", promotion = "5.0000")
                }

                transaction {
                    val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)

                    assertEquals(
                        "18.00",
                        overview.totalDiscounts,
                        "2.00 line discount + 3.00 bill-wide + 8.00 item promotions + 5.00 Neubuchung; " +
                            "the reversed sale and both Stornos must not count",
                    )
                    // Revenue is unchanged by the fix: Σ paidAmount of the three active bills.
                    assertEquals("79.00", overview.totalRevenue)
                }
            },
        ) { _ -> }
    }
}
