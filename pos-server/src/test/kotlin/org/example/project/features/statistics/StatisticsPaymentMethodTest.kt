package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The per-method figures and the Zahlungsart filter of the statistics, over ordinary bills: a split
 * payment counts one payment per method and is found by each of its methods, and a cancelled bill
 * stays next to its Storno under the tender that was refunded.
 *
 * Bills are seeded as BillServiceImpl writes them: a Storno points `deletedBill` at the original,
 * marks the original with `deletingReason`, and refunds the original's own payment rows
 * (REFUNDED, `refundedBillId` = the Storno).
 */
@DisplayName("Statistics — payment methods and the Zahlungsart filter")
class StatisticsPaymentMethodTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val start = LocalDate(2026, 9, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 9, 30).atTime(23, 59, 59)
    private val at = LocalDate(2026, 9, 27).atTime(12, 0, 0)

    private lateinit var cashier: Employee
    private lateinit var session: TableSession
    private lateinit var bar: PaymentType
    private lateinit var ec: PaymentType
    private lateinit var kk: PaymentType

    private fun seedBase() {
        TestData.createShopInfo()
        bar = TestData.createPaymentType(name = "BAR", isCash = true)
        ec = TestData.createPaymentType(name = "EC-KARTE", isCash = false)
        kk = TestData.createPaymentType(name = "KREDITKARTE", isCash = false)
        cashier = TestData.createCashier(employeeNumber = "PMA", username = "pma")
        session = TestData.createTableWithSession("PMS").session
    }

    private fun bill(
        number: String,
        amount: String,
        configure: Bill.() -> Unit = {},
    ) = Bill.new {
        this.employeeId = cashier
        this.tableSession = session
        this.billNumber = number
        this.billAmount = BigDecimal(amount)
        this.paidAmount = BigDecimal(amount)
        this.startTime = at
        this.endTime = at
        this.isTraining = false
        configure()
    }

    private fun pay(
        bill: Bill,
        type: PaymentType,
        amount: String,
        refundedBy: Bill? = null,
    ) = PaymentTable.insertAndGetId {
        it[billId] = bill.id
        it[paymentTypeId] = type.id
        it[this.amount] = BigDecimal(amount)
        it[paymentStatus] = if (refundedBy == null) PaymentStatus.COMPLETED else PaymentStatus.REFUNDED
        it[paymentTime] = at
        it[currency] = "EUR"
        it[refundedBillId] = refundedBy?.id
    }

    /** One bill paid 10.00 BAR + 20.00 EC. */
    private fun seedSplit() {
        seedBase()
        transaction {
            val bill = bill("RE-1", "30.00")
            pay(bill, bar, "10.00")
            pay(bill, ec, "20.00")
        }
    }

    /** RE-0 paid 10.60 EC and stornoed by RE-S, plus an untouched cash sale RE-9. */
    private fun seedCancelled() {
        seedBase()
        transaction {
            pay(bill("RE-9", "26.60"), bar, "26.60")
            val original = bill("RE-0", "10.60")
            original.deletingReason = "RE-S : Falsch gebucht"
            val storno = bill("RE-S", "-10.60") { deletedBill = original }
            pay(original, ec, "10.60", refundedBy = storno)
        }
    }

    private fun List<PaymentDto.PaymentSummary>.countOf(type: String): Int = filter { it.paymentTypeDE == type }.sumOf { it.paymentCount }

    private fun Map<String, PaymentMethodSummaryDto>.countOf(type: String): Int = this[type]?.transactionCount ?: 0

    private fun assertMoney(
        expected: String,
        actual: BigDecimal,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, was ${actual.toPlainString()}")

    private fun billNumbersPaidWith(vararg types: String): List<String> =
        transaction {
            repo
                .getPaginatedBills(start, end, null, 0, 50, StatisticsDto.StatisticsSortOption.OLDEST, false, types.toList())
                .bills
                .map { it.billNumber }
                .sorted()
        }

    @Test
    fun `a split payment counts one payment per method`() =
        testApp(setupData = { seedSplit() }) { _ ->
            val overview = transaction { repo.getSalesOverviewSummary(start, end, "EUR", null, false) }
            assertEquals(1, overview.revenueByPaymentMethod.countOf("BAR"), "overview BAR payments")
            assertEquals(1, overview.revenueByPaymentMethod.countOf("EC-KARTE"), "overview EC payments")
            assertEquals(1, overview.totalBillCount, "one Rechnung")

            val financial = transaction { repo.getFinancialOverview(start, end, "EUR", null, false) }
            assertEquals(1, financial.paymentMethodBreakdown.countOf("BAR"), "financial BAR payments")
            assertEquals(1, financial.paymentMethodBreakdown.countOf("EC-KARTE"), "financial EC payments")
            assertMoney("10.00", BigDecimal(financial.paymentMethodBreakdown.getValue("BAR").averageTransactionValue), "Ø BAR")
            assertMoney("20.00", BigDecimal(financial.paymentMethodBreakdown.getValue("EC-KARTE").averageTransactionValue), "Ø EC")
        }

    /**
     * UI review follow-up 7: the Finanzübersicht names a tender in the UI language, so each row also
     * carries the type's English and Chinese names. Display only: the key, the grouping and every
     * figure stay per German master name, and a type without a translation sends null.
     */
    @Test
    fun `the financial payment breakdown carries the translated names without changing a figure`() =
        testApp(
            setupData = {
                seedSplit()
                transaction {
                    bar.enName = "CASH"
                    bar.chName = "现金"
                }
            },
        ) { _ ->
            val financial = transaction { repo.getFinancialOverview(start, end, "EUR", null, false) }
            assertEquals(setOf("BAR", "EC-KARTE"), financial.paymentMethodBreakdown.keys, "keys stay the German names")
            val barRow = financial.paymentMethodBreakdown.getValue("BAR")
            assertEquals("BAR", barRow.paymentMethod)
            assertEquals("CASH", barRow.paymentMethodEN)
            assertEquals("现金", barRow.paymentMethodCH)
            assertEquals(1, barRow.transactionCount)
            assertMoney("10.00", BigDecimal(barRow.totalAmount), "BAR total")
            val ecRow = financial.paymentMethodBreakdown.getValue("EC-KARTE")
            assertEquals(null, ecRow.paymentMethodEN, "no translation stored")
            assertEquals(null, ecRow.paymentMethodCH, "no translation stored")
            assertMoney("20.00", BigDecimal(ecRow.totalAmount), "EC total")
        }

    @Test
    fun `a cancelled bill and its storno stay together under the tender that was refunded`() =
        testApp(setupData = { seedCancelled() }) { _ ->
            assertEquals(listOf("RE-9"), billNumbersPaidWith("BAR"), "BAR filter")
            assertEquals(listOf("RE-0", "RE-S"), billNumbersPaidWith("EC-KARTE"), "EC filter")
        }

    @Test
    fun `a split bill is found by each of its methods`() =
        testApp(setupData = { seedSplit() }) { _ ->
            assertEquals(listOf("RE-1"), billNumbersPaidWith("BAR"), "BAR filter")
            assertEquals(listOf("RE-1"), billNumbersPaidWith("EC-KARTE"), "EC filter")
            assertEquals(listOf("RE-1"), billNumbersPaidWith("BAR", "EC-KARTE"), "BAR or EC lists it once")
            assertEquals(emptyList(), billNumbersPaidWith("KREDITKARTE"), "KK filter")
        }
}
