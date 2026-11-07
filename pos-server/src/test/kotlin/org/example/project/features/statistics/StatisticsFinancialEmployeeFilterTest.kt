package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
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

@DisplayName("Financial overview — one employee filter for every figure (E2E-2026-09-18 #9)")
class StatisticsFinancialEmployeeFilterTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val start = LocalDate(2026, 9, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 9, 30).atTime(23, 59, 59)
    private val at = LocalDate(2026, 9, 18).atTime(12, 0, 0)

    private lateinit var cashierA: Employee

    /** Two cashiers, one paid bill each, the cash tender named BAR (cash by flag, not by name). */
    private fun seed() {
        TestData.createShopInfo()
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "BAR", isCash = true)
        cashierA = TestData.createCashier(employeeNumber = "FEA", username = "fea")
        val cashierB = TestData.createCashier(employeeNumber = "FEB", username = "feb")
        val tws = TestData.createTableWithSession("FEF")
        transaction {
            listOf(Triple(cashierA, "11.9000", "10.0000"), Triple(cashierB, "23.8000", "20.0000"))
                .forEachIndexed { i, (emp, gross, net) ->
                    val bill =
                        Bill.new {
                            this.employeeId = emp
                            this.tableSession = tws.session
                            this.billNumber = "FEF-$i"
                            this.billAmount = BigDecimal(gross)
                            this.paidAmount = BigDecimal(gross)
                            this.startTime = at
                            this.endTime = at
                            this.isTraining = false
                        }
                    PaymentTable.insert {
                        it[billId] = bill.id
                        it[paymentTypeId] = cash.id
                        it[amount] = BigDecimal(gross)
                        it[paymentStatus] = PaymentStatus.COMPLETED
                        it[paymentTime] = at
                        it[currency] = "EUR"
                    }
                    BillVatDetailTable.insert {
                        it[billId] = bill.id
                        it[vatId] = vat.id
                        it[netAmount] = BigDecimal(net)
                        it[vatAmount] = BigDecimal(gross) - BigDecimal(net)
                        it[totalAmount] = BigDecimal(gross)
                    }
                }
        }
    }

    @Test
    fun `an employee-filtered overview reports that employee's VAT, not the shop's`() =
        testApp(setupData = { seed() }) { _ ->
            val dto = transaction { repo.getFinancialOverview(start, end, "EUR", cashierA, false) }

            assertEquals("11.90", dto.grossRevenue)
            assertEquals("10.00", dto.netRevenue, "was 11.90 - (1.90 + 3.80) = 6.20 with the shop-wide VAT")
            assertEquals(
                "1.90",
                dto.vatBreakdown.values
                    .single()
                    .vatAmount,
            )
            assertEquals("11.90", dto.cashFlow.cashSales, "BAR is cash by its is_cash flag")
        }

    @Test
    fun `the unfiltered overview is shop-wide`() =
        testApp(setupData = { seed() }) { _ ->
            val dto = transaction { repo.getFinancialOverview(start, end, "EUR", null, false) }

            assertEquals("35.70", dto.grossRevenue)
            assertEquals("30.00", dto.netRevenue)
            assertEquals("35.70", dto.cashFlow.cashSales)
        }
}
