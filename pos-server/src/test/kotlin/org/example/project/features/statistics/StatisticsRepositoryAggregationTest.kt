package org.example.project.features.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
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
 * Repository-level tests for the consolidated statistics aggregation queries.
 *
 * Verifies that the single-scan bill classification in [StatisticsRepositoryImpl.getSalesOverviewSummary]
 * (active vs. storno vs. cancelled vs. training + active-only discount total) and the
 * single-join fetch in [StatisticsRepositoryImpl.getCanceledItems] produce the same
 * results as the previous multi-query implementations.
 */
@DisplayName("Statistics repository aggregation queries")
class StatisticsRepositoryAggregationTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    @Test
    fun `getSalesOverviewSummary classifies active, storno, cancelled and training bills and sums active-only discounts`() {
        testApp(
            setupData = {
                TestData.createAdmin()
                val normal = TestData.createTableWithSession("AGG1")
                val trainingTws = TestData.createTableWithSession("AGG2")
                val billTime = today.atTime(12, 0, 0)

                transaction {
                    val session = normal.session
                    val employee = session.employeeId

                    // Active bill: paid = billAmount + tips; discount counts toward totalDiscounts.
                    // The recipient is stated rather than left to the column default, because it is
                    // now load-bearing: an EMPLOYEE tip (TrinkgeldAN) is not turnover. EMPLOYEE is
                    // also what production would derive here — this session's closer is the CASHIER
                    // TestData.createTableWithSession makes, and BillRepositoryImpl gives every
                    // non-ADMIN closer TrinkgeldAN.
                    Bill.new {
                        this.employeeId = employee
                        this.tableSession = session
                        this.billNumber = "AGG-ACTIVE"
                        this.billAmount = BigDecimal("20.0000")
                        this.paidAmount = BigDecimal("22.0000")
                        this.tips = BigDecimal("2.0000")
                        this.tipRecipient = TipRecipient.EMPLOYEE
                        this.discountAmount = BigDecimal("5.0000")
                        this.startTime = billTime
                        this.endTime = billTime
                        this.isTraining = false
                    }

                    // Cancelled original (deletingReason set): excluded from revenue + discounts
                    val cancelled =
                        Bill.new {
                            this.employeeId = employee
                            this.tableSession = session
                            this.billNumber = "AGG-CANCELLED"
                            this.billAmount = BigDecimal("10.0000")
                            this.paidAmount = BigDecimal("10.0000")
                            this.discountAmount = BigDecimal("3.0000")
                            this.startTime = billTime
                            this.endTime = billTime
                            this.isTraining = false
                        }

                    // Storno / reverse bill (deletedBillId set): negative amounts, feeds refundRevenue
                    Bill.new {
                        this.employeeId = employee
                        this.tableSession = session
                        this.billNumber = "AGG-STORNO"
                        this.billAmount = BigDecimal("-10.0000")
                        this.paidAmount = BigDecimal("-10.0000")
                        this.discountAmount = BigDecimal("3.0000")
                        this.startTime = billTime
                        this.endTime = billTime
                        this.deletedBill = cancelled
                        this.isTraining = false
                    }
                    cancelled.deletingReason = "wrong order"

                    // Training bill on a training session: excluded everywhere
                    val trainingSession = trainingTws.session
                    trainingSession.isTraining = true
                    Bill.new {
                        this.employeeId = trainingSession.employeeId
                        this.tableSession = trainingSession
                        this.billNumber = "AGG-TRAINING"
                        this.billAmount = BigDecimal("99.0000")
                        this.paidAmount = BigDecimal("99.0000")
                        this.discountAmount = BigDecimal("7.0000")
                        this.startTime = billTime
                        this.endTime = billTime
                        this.isTraining = true
                    }
                }

                transaction {
                    val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)

                    // Active bill only. This expectation was 22.00 — the bill's whole paidAmount —
                    // until plan 2026-09-16 P1.4 (E2E-2026-09-15 N1). 2.00 of that 22.00 is an
                    // EMPLOYEE tip (TrinkgeldAN): non-taxable, and not turnover, so it is no longer
                    // counted as revenue. The tip did not disappear — totalTips below still reports
                    // the full 2.00. The Z-Bon and the DSFinV-K export have always derived revenue
                    // this way; statistics was the outlier.
                    assertEquals(
                        "20.00",
                        overview.totalRevenue,
                        "totalRevenue = paidAmount of active bills, less the employee tips inside it",
                    )
                    assertEquals("20.00", overview.billedRevenue, "billedRevenue = billAmount of active bills")
                    assertEquals("2.00", overview.totalTips, "totalTips from active bills")
                    assertEquals(1, overview.totalBillCount, "storno/cancelled/training bills excluded from count")

                    // Discounts only from active bills (5.00), not cancelled (3), storno (3) or training (7)
                    assertEquals("5.00", overview.totalDiscounts)

                    // Refund = negated paidAmount of storno bills
                    assertEquals("10.00", overview.refundRevenue)

                    // Only the non-training session
                    assertEquals(1, overview.totalSessionCount)

                    assertEquals("0.00", overview.canceledRevenue)
                    assertEquals("0.00", overview.openItemAmount)
                }
            },
        ) { _ -> }
    }

    @Test
    fun `getCanceledItems returns joined item, bill, product and employee data in one query`() {
        testApp(
            setupData = {
                TestData.createAdmin()
                val category = TestData.createCategory(name = "AggCat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Schnitzel",
                        productNumber = "AGG-P1",
                    )
                val vat = TestData.createDefaultVatRates().first()
                val tws = TestData.createTableWithSession("AGG3")
                val earlier = today.atTime(10, 0, 0)
                val later = today.atTime(11, 0, 0)

                transaction {
                    val session = tws.session
                    val employee = session.employeeId

                    val bill =
                        Bill.new {
                            this.employeeId = employee
                            this.tableSession = session
                            this.billNumber = "AGG-BILL-1"
                            this.billAmount = BigDecimal("4.5000")
                            this.paidAmount = BigDecimal("4.5000")
                            this.startTime = earlier
                            this.endTime = earlier
                            this.isTraining = false
                        }

                    val itemWithBill =
                        OrderItem.new {
                            this.session = session
                            this.bill = bill
                            this.product = product
                            this.unitPrice = BigDecimal("4.5000")
                            this.finalPrice = BigDecimal("4.5000")
                            this.itemStatus = ItemStatus.CANCELLED
                            this.vat = vat
                            this.takeAway = false
                        }
                    val itemWithoutBill =
                        OrderItem.new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("3.2500")
                            this.finalPrice = BigDecimal("3.2500")
                            this.itemStatus = ItemStatus.CANCELLED
                            this.vat = vat
                            this.takeAway = false
                        }
                    val trainingItem =
                        OrderItem.new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("50.0000")
                            this.finalPrice = BigDecimal("50.0000")
                            this.itemStatus = ItemStatus.CANCELLED
                            this.vat = vat
                            this.takeAway = false
                        }

                    Cancellation.new {
                        this.orderItem = itemWithBill
                        this.employeeId = employee
                        this.createdAt = earlier
                        this.reason = "dropped"
                    }
                    Cancellation.new {
                        this.orderItem = itemWithoutBill
                        this.employeeId = employee
                        this.createdAt = later
                    }
                    // Training cancellation must be filtered out
                    Cancellation.new {
                        this.orderItem = trainingItem
                        this.employeeId = employee
                        this.createdAt = later
                        this.isTraining = true
                    }
                }

                transaction {
                    val result = repo.getCanceledItems(rangeStart, rangeEnd, null, false)

                    assertEquals(2, result.totalCount, "training cancellation excluded")
                    assertEquals("7.75", result.totalAmount)

                    // Ordered by createdAt DESC: item without bill (later) first
                    val first = result.canceledItems[0]
                    assertEquals("Schnitzel", first.itemName)
                    assertEquals("3.25", first.unitPrice)
                    assertEquals("3.25", first.totalAmount)
                    assertEquals("-", first.billNumber, "no bill -> placeholder")
                    assertEquals("", first.reason, "null reason -> empty string")
                    assertEquals(1, first.quantity)

                    val second = result.canceledItems[1]
                    assertEquals("Schnitzel", second.itemName)
                    assertEquals("4.50", second.unitPrice)
                    assertEquals("4.50", second.totalAmount)
                    assertEquals("AGG-BILL-1", second.billNumber)
                    assertEquals("dropped", second.reason)
                }
            },
        ) { _ -> }
    }
}
