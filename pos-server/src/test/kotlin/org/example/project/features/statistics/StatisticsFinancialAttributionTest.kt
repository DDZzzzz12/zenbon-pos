package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.database.tables.BillVatDetailTable
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

@DisplayName("Financial overview Z-Bon attribution")
class StatisticsFinancialAttributionTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val zbonStart = LocalDate(2026, 5, 31).atTime(23, 0, 0)
    private val zbonEnd = LocalDate(2026, 6, 1).atTime(22, 0, 0)
    private val mayStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val mayEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val juneStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val juneEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)
    private val openBillTime = LocalDate(2026, 6, 15).atTime(12, 0, 0)
    private val openBillBefore = LocalDate(2026, 6, 15).atTime(11, 59, 59)

    @Test
    fun `a late bill's VAT and payment book to its Z-Bon's month, not its own`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
                val cash = TestData.createPaymentType(name = "Cash")
                val tws = TestData.createTableWithSession("FINATTR")
                val zbon =
                    TestData.createZBon(
                        shop = shop,
                        employee = admin,
                        start = zbonStart,
                        end = zbonEnd,
                        nummer = 1L,
                    )
                transaction {
                    val bill =
                        Bill.new {
                            this.employeeId = tws.session.employeeId
                            this.tableSession = tws.session
                            this.billNumber = "FIN-LATE"
                            this.billAmount = BigDecimal("11.9000")
                            this.paidAmount = BigDecimal("11.9000")
                            this.startTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                            this.endTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                            this.isTraining = false
                            this.zBon = zbon
                        }
                    PaymentTable.insert {
                        it[billId] = bill.id
                        it[paymentTypeId] = cash.id
                        it[amount] = BigDecimal("11.9000")
                        it[paymentStatus] = PaymentStatus.COMPLETED
                        it[paymentTime] = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                        it[currency] = "EUR"
                    }
                    BillVatDetailTable.insert {
                        it[billId] = bill.id
                        it[vatId] = vat.id
                        it[netAmount] = BigDecimal("10.0000")
                        it[vatAmount] = BigDecimal("1.9000")
                        it[totalAmount] = BigDecimal("11.9000")
                    }
                }
            },
        ) { _ ->
            transaction {
                val may = repo.getFinancialOverview(mayStart, mayEnd, "EUR", null, false)
                assertEquals("0.00", may.grossRevenue, "May must not claim a bill whose Z-Bon closed in June")

                val june = repo.getFinancialOverview(juneStart, juneEnd, "EUR", null, false)
                assertEquals("11.90", june.grossRevenue, "June owns the whole Z-Bon's gross")
                assertEquals(
                    "1.90",
                    june.vatBreakdown.values
                        .single()
                        .vatAmount,
                    "and its VAT",
                )
                assertEquals("11.90", june.paymentMethodBreakdown.getValue("Cash").totalAmount, "and its cash payment")
            }
        }

    /**
     * A bill that has not yet been closed into a Z-Bon must be attributed to when it was settled,
     * not to "now".
     *
     * The fallback used to be `dateTimeLiteral(currentTimeStamp())`, rendered when the query was
     * BUILT. An un-booked bill therefore carried an attributed time of the moment the report ran, so
     * any window ending even fractionally earlier excluded it — a flaky, data-dependent under-count
     * that got worse the longer a shift stayed open. This pins the boundary case: the window ends at
     * exactly the bill's endTime, which `lessEq` must include.
     */
    @Test
    fun `an un-booked bill is attributed to its own endTime, not to now`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                TestData.createShopInfo()
                val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
                val cash = TestData.createPaymentType(name = "Cash")
                val tws = TestData.createTableWithSession("FINOPEN")
                transaction {
                    val bill =
                        Bill.new {
                            this.employeeId = tws.session.employeeId
                            this.tableSession = tws.session
                            this.billNumber = "FIN-OPEN"
                            this.billAmount = BigDecimal("11.9000")
                            this.paidAmount = BigDecimal("11.9000")
                            this.startTime = openBillTime
                            this.endTime = openBillTime
                            this.isTraining = false
                            // Deliberately no Z-Bon: this is the fallback leg.
                        }
                    PaymentTable.insert {
                        it[billId] = bill.id
                        it[paymentTypeId] = cash.id
                        it[amount] = BigDecimal("11.9000")
                        it[paymentStatus] = PaymentStatus.COMPLETED
                        it[paymentTime] = openBillTime
                        it[currency] = "EUR"
                    }
                    BillVatDetailTable.insert {
                        it[billId] = bill.id
                        it[vatId] = vat.id
                        it[netAmount] = BigDecimal("10.0000")
                        it[vatAmount] = BigDecimal("1.9000")
                        it[totalAmount] = BigDecimal("11.9000")
                    }
                }
                admin
            },
        ) { _ ->
            transaction {
                val exact = repo.getFinancialOverview(juneStart, openBillTime, "EUR", null, false)
                assertEquals(
                    "11.90",
                    exact.grossRevenue,
                    "a window ending exactly at the bill's endTime must include it; " +
                        "the old now() fallback attributed it to report time and dropped it",
                )

                val before = repo.getFinancialOverview(juneStart, openBillBefore, "EUR", null, false)
                assertEquals(
                    "0.00",
                    before.grossRevenue,
                    "and a window ending one second earlier must still exclude it",
                )
            }
        }
}
