package org.example.project.features.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * A Zahlartwechsel (Storno + Neubuchung) refunds nothing — its sale is booked again at once — so
 * the statistics leave its Storno out of the refund figures. An ordinary Storno stays a refund.
 * Counts already exclude the stornoed original and its Storno; the Neubuchung counts once.
 */
@DisplayName("Statistics — a Zahlartwechsel Storno is not a refund")
class StatisticsRebookRefundTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val fixture = RebookFixture("stats-rebook-device")

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun window(): Pair<LocalDateTime, LocalDateTime> {
        val today = currentTimeStamp().date
        return today.minus(1, DateTimeUnit.DAY).atTime(0, 0) to today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)
    }

    private fun assertMoney(
        expected: String,
        actual: String,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "$what: expected $expected, was $actual")

    /** A 23.00 card sale rebooked to card 25.00 with a 2.00 tip, and an ordinary Storno of a 10.00 cash sale. */
    private fun rebookOneStornoAnother() {
        val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
        fixture.rebook(
            sale,
            BillDto.RebookRequestDto(
                payments = listOf(PaymentDto.PaymentRequest(amount = "25.00", paymentTypeId = sale.cardTypeId)),
                tips = "2.00",
            ),
        )
        fixture.storno(fixture.closeSale(SaleSpec(tender = Tender.CASH, items = listOf("10.00" to 0))))
    }

    @Test
    fun `sales overview counts the Neubuchung once and reports only the ordinary Storno as a refund`() =
        testApp(setupData = { TestData.setTseMode(TseMode.DISABLED) }) { _ ->
            rebookOneStornoAnother()
            val (start, end) = window()

            val overview = transaction { repo.getSalesOverviewSummary(start, end, "EUR", null, false) }

            assertEquals(1, overview.totalBillCount, "only the Neubuchung is a sale")
            assertMoney("23.00", overview.totalRevenue, "revenue excluding the employee tip")
            assertMoney("2.00", overview.totalTips, "the Neubuchung's tip")
            assertMoney("10.00", overview.refundRevenue, "the Zahlartwechsel Storno is not a refund")
        }

    @Test
    fun `financial overview reports only the ordinary Storno as a refund`() =
        testApp(setupData = { TestData.setTseMode(TseMode.DISABLED) }) { _ ->
            rebookOneStornoAnother()
            val (start, end) = window()

            val overview = transaction { repo.getFinancialOverview(start, end, "EUR", null, false) }

            assertMoney("10.00", overview.totalRefunds, "the Zahlartwechsel Storno is not a refund")
            val day = overview.dailyFinancialSummary.values.single()
            assertEquals(1, day.transactionCount, "one sale on the day")
        }
}
