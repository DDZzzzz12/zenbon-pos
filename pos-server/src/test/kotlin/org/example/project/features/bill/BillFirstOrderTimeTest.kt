package org.example.project.features.bill

import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.getFirstOrderTseStartTime
import org.example.project.features.bill.mapper.toResponseDto
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Round-3 TSER-3 guard: the Tz.-2.7.2 Erleichterung (payment-time Kassenbeleg + per-order
 * Bestellung-V1 signing) requires the FIRST Bestellung's StartTransaction log time to be
 * available for printing on the Beleg — the START time, NOT the finish time.
 *
 * [Bill.getFirstOrderTseStartTime] must return the first order's `tseStartTimestamp`
 * (1750000000L here), never its finish `tseTimestamp` (1750000099L), and
 * [Bill.toResponseDto] must surface it as `firstOrderTseStartTime` so the printer can read it.
 */
@DisplayName("Bill first-order TSE start time (TSER-3)")
class BillFirstOrderTimeTest {
    private val startSeconds = 1750000000L
    private val finishSeconds = 1750000099L

    @Test
    fun `getFirstOrderTseStartTime and DTO expose the first order's StartTransaction time`() =
        testApp(
            setupData = {
                val tws = TestData.createTableWithSession("TSER3")

                val billId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        val employee = session.employeeId
                        val now = currentTimeStamp()

                        val orderTse =
                            TseTransaction.new {
                                this.transactionNumber = 42L
                                this.processType = "Bestellung-V1"
                                this.tseStartTimestamp = startSeconds
                                this.tseTimestamp = finishSeconds
                            }

                        Orders.new {
                            this.orderNumber = "ORD-TSER3-1"
                            this.employee = employee
                            this.tableSession = session
                            this.orderTime = now
                            this.orderTotal = BigDecimal("10.0000")
                            this.orderStatus = OrderStatus.CONFIRMED
                            this.tseTransaction = orderTse
                        }

                        Bill
                            .new {
                                this.employeeId = employee
                                this.tableSession = session
                                this.billNumber = "BILL-TSER3-1"
                                this.billAmount = BigDecimal("10.0000")
                                this.paidAmount = BigDecimal("10.0000")
                                this.startTime = now
                                this.endTime = now
                            }.id.value
                    }

                transaction {
                    val bill = Bill.findById(billId)!!
                    assertEquals(
                        startSeconds,
                        bill.getFirstOrderTseStartTime(),
                        "must return the first order's StartTransaction log time ($startSeconds), " +
                            "not the finish time ($finishSeconds)",
                    )
                    assertEquals(
                        startSeconds,
                        bill.toResponseDto(SalesInvoiceBillInvoiceLink()).firstOrderTseStartTime,
                        "toResponseDto must surface the first order's start time as firstOrderTseStartTime",
                    )
                }
            },
        ) { _ -> }
}
