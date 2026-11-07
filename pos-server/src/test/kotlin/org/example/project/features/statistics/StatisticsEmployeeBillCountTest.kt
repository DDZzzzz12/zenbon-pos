package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** E2E-2026-09-18 #20: an employee's bill count counts sales, not the storno pair. */
class StatisticsEmployeeBillCountTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))
    private val start = LocalDate(2026, 9, 1).atTime(0, 0, 0)
    private val end = LocalDate(2026, 9, 30).atTime(23, 59, 59)
    private val at = LocalDate(2026, 9, 18).atTime(12, 0, 0)

    @Test
    fun `a cancelled bill and its reversal do not count as sales`() =
        testApp(setupData = {
            TestData.createShopInfo()
            val tws = TestData.createTableWithSession("SBC")
            transaction {
                fun bill(
                    number: String,
                    amount: String,
                    configure: Bill.() -> Unit = {},
                ) = Bill.new {
                    this.employeeId = tws.session.employeeId
                    this.tableSession = tws.session
                    this.billNumber = number
                    this.billAmount = BigDecimal(amount)
                    this.paidAmount = BigDecimal(amount)
                    this.startTime = at
                    this.endTime = at
                    this.isTraining = false
                    configure()
                }
                bill("SBC-1", "10.00")
                val cancelled = bill("SBC-2", "20.00") { deletingReason = "Falsch gebucht" }
                bill("SBC-3", "-20.00") { deletedBill = cancelled }
            }
        }) { _ ->
            val groups =
                transaction {
                    repo.getPaginatedEmployeeGroups(start, end, null, 0, 50, StatisticsDto.StatisticsSortOption.RECENT, false, null)
                }

            assertEquals(1, groups.employeeGroups.single().billCount)
        }
}
