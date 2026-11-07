package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `getHourlySalesDistribution` summed the payments of every bill. A Zahlartwechsel across an hour
 * boundary — original at 10:55, Storno + Neubuchung at 11:05 — then showed the whole sale at 10
 * o'clock and a net zero at 11. With the active-bill rule only the Neubuchung counts, at 11.
 */
@DisplayName("Hourly distribution counts active bills only")
class StatisticsHourlyDistributionActiveBillsTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val day = LocalDate(2026, 10, 1)

    @Test
    fun `a Zahlartwechsel across an hour boundary counts once, in the Neubuchung's hour`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val cash = TestData.createPaymentType(name = "Cash")
                val card = TestData.createPaymentType(name = "Card")
                val tws = TestData.createTableWithSession("HOUR")
                transaction {
                    val session = tws.session

                    fun bill(
                        number: String,
                        amount: String,
                        at: LocalDateTime,
                        paidWith: org.example.project.features.payment.domain.models.PaymentType,
                    ): Bill {
                        val bill =
                            Bill.new {
                                this.employeeId = session.employeeId
                                this.tableSession = session
                                this.billNumber = number
                                this.billAmount = BigDecimal(amount)
                                this.paidAmount = BigDecimal(amount)
                                this.startTime = at
                                this.endTime = at
                                this.isTraining = false
                            }
                        PaymentTable.insert {
                            it[billId] = bill.id
                            it[paymentTypeId] = paidWith.id
                            it[this.amount] = BigDecimal(amount)
                            it[paymentStatus] = PaymentStatus.COMPLETED
                            it[paymentTime] = at
                            it[currency] = "EUR"
                        }
                        return bill
                    }

                    val original = bill("HOUR-1", "17.90", day.atTime(10, 55), cash)
                    bill("HOUR-1-ST", "-17.90", day.atTime(11, 5), cash).apply {
                        deletedBill = original
                        stornoReason = StornoReason.ZAHLARTWECHSEL
                    }
                    original.deletingReason = "HOUR-1-ST : Zahlartwechsel"
                    bill("HOUR-1-NEU", "17.90", day.atTime(11, 5), card).apply { rebooksBill = original }
                }
            },
        ) { _ ->
            transaction {
                val hourly = repo.getHourlySalesDistribution(day)
                assertEquals(setOf(11), hourly.keys, "the cancelled original's 10 o'clock bucket must not appear")
                assertEquals(0, BigDecimal("17.90").compareTo(BigDecimal(hourly.getValue(11))), "only the Neubuchung: ${hourly[11]}")
            }
        }
}
