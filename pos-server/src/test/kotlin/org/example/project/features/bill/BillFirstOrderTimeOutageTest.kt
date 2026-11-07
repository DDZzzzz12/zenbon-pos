package org.example.project.features.bill

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.mapper.getFirstOrderTseStartTime
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * M9: what the Beleg discloses about the start of ordering when the TSE was down while ordering.
 *
 * This system claims the Tz. 2.7.2 Erleichterung — the Kassenbeleg-V1 transaction opens only at
 * payment, and the orders are secured individually as Bestellung-V1. The relief is conditional:
 * „Der Start-Zeitpunkt der ersten Transaktion ‚Bestellung' muss zusätzlich auf dem Beleg
 * abgedruckt werden." That printed timestamp is the only thing on the Beleg that says when the
 * Vorgang really began; without it the payment-time Kassenbeleg stands alone and a two-hour visit
 * looks like an instant sale.
 *
 * `getFirstOrderTseStartTime` filtered to orders that HAVE a TSE transaction, which failed in two
 * different ways during an outage — and the worse one was silent:
 *
 *  1. **Wrong value.** Outage covers the 18:20 order, the TSE recovers, the 19:05 order signs
 *     normally. The filter drops the unsigned first order, so the Beleg printed 19:05 and asserted
 *     that ordering began 45 minutes after it actually did. A confidently wrong disclosure.
 *  2. **Omitted entirely.** No order signed at all, so it returned null and
 *     `GenerateBillReceipt` skipped the line — the relief's precondition quietly unmet while the
 *     relief was still being relied on.
 *
 * The first order is the first order whether or not the TSE was reachable when it was entered.
 * Resolve it from ALL orders, then prefer that order's TSE start time and fall back to the till's
 * own capture time. A slightly-off timestamp on the Beleg is a far smaller problem than a
 * confidently wrong one or a missing one.
 */
@DisplayName("First-order disclosure survives a TSE outage (M9 / Tz. 2.7.2)")
class BillFirstOrderTimeOutageTest {
    private val firstOrderAt = LocalDateTime(2026, 7, 15, 18, 20, 0)
    private val secondOrderAt = LocalDateTime(2026, 7, 15, 19, 5, 0)
    private val secondOrderSignedAt = 1_784_000_000L

    @Test
    fun `an unsigned first order is still the first order`() =
        testApp(
            setupData = {
                val tws = TestData.createTableWithSession("M9A")

                val billId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        val employee = session.employeeId

                        // TSE was down: entered, never signed.
                        order(session, employee, firstOrderAt, "M9A-1", null)

                        // TSE recovered before the second round.
                        val signed =
                            TseTransaction.new {
                                this.transactionNumber = 7L
                                this.processType = "Bestellung-V1"
                                this.tseStartTimestamp = secondOrderSignedAt
                            }
                        order(session, employee, secondOrderAt, "M9A-2", signed)

                        bill(session, employee, "M9A-BILL")
                    }

                transaction {
                    assertEquals(
                        firstOrderAt.toInstant(getTimeZone()).epochSeconds,
                        Bill.findById(billId)!!.getFirstOrderTseStartTime(),
                        "the 18:20 order started this Vorgang. Skipping it because the TSE happened to " +
                            "be down and printing the 19:05 signature instead tells the auditor " +
                            "ordering began 45 minutes later than it did.",
                    )
                }
            },
        ) { _ -> }

    @Test
    fun `no order signed at all still discloses when ordering began`() =
        testApp(
            setupData = {
                val tws = TestData.createTableWithSession("M9B")

                val billId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        val employee = session.employeeId
                        order(session, employee, firstOrderAt, "M9B-1", null)
                        order(session, employee, secondOrderAt, "M9B-2", null)
                        bill(session, employee, "M9B-BILL")
                    }

                transaction {
                    assertEquals(
                        firstOrderAt.toInstant(getTimeZone()).epochSeconds,
                        Bill.findById(billId)!!.getFirstOrderTseStartTime(),
                        "returning null drops the line from the Beleg entirely, so the Tz. 2.7.2 " +
                            "precondition goes unmet while the relief is still relied on. The capture " +
                            "time is known — it must be disclosed.",
                    )
                }
            },
        ) { _ -> }

    /** A signed first order must still win: the TSE time stays authoritative when it exists. */
    @Test
    fun `a signed first order still reports its TSE start time`() =
        testApp(
            setupData = {
                val tws = TestData.createTableWithSession("M9C")

                val billId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        val employee = session.employeeId
                        val signed =
                            TseTransaction.new {
                                this.transactionNumber = 9L
                                this.processType = "Bestellung-V1"
                                this.tseStartTimestamp = secondOrderSignedAt
                            }
                        order(session, employee, firstOrderAt, "M9C-1", signed)
                        bill(session, employee, "M9C-BILL")
                    }

                transaction {
                    assertEquals(
                        secondOrderSignedAt,
                        Bill.findById(billId)!!.getFirstOrderTseStartTime(),
                        "the fallback must not displace a real TSE timestamp",
                    )
                }
            },
        ) { _ -> }

    private fun order(
        session: TableSession,
        employee: org.example.project.features.employee.domain.models.Employee,
        at: LocalDateTime,
        number: String,
        tse: TseTransaction?,
    ) = Orders.new {
        this.orderNumber = number
        this.employee = employee
        this.tableSession = session
        this.orderTime = at
        this.orderTotal = BigDecimal("10.0000")
        this.orderStatus = OrderStatus.CONFIRMED
        this.tseTransaction = tse
    }

    private fun bill(
        session: TableSession,
        employee: org.example.project.features.employee.domain.models.Employee,
        number: String,
    ) = Bill
        .new {
            this.employeeId = employee
            this.tableSession = session
            this.billNumber = number
            this.billAmount = BigDecimal("20.0000")
            this.paidAmount = BigDecimal("20.0000")
            this.startTime = firstOrderAt
            this.endTime = secondOrderAt
        }.id.value
}
