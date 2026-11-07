package org.example.project.features.monthlyclose.checks

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.monthlyclose.data.service.checks.OpenBillsCheck
import org.example.project.features.table.domain.models.TableSession
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class OpenBillsCheckTest {
    private val startLdt = LocalDateTime(2026, 5, 1, 0, 0, 0)
    private val endLdt = LocalDateTime(2026, 6, 1, 0, 0, 0)

    @Test
    fun `PASSED when zero open bills in window`() =
        runBlocking {
            val repo = StubBillRepository(openCount = 0)
            val check = OpenBillsCheck(repo)

            val result = check.run(startLdt, endLdt)

            assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, result.status)
            assertEquals("no_open_bills", result.id)
            assertEquals(0, result.affectedCount)
        }

    @Test
    fun `FAILED when one or more open bills in window`() =
        runBlocking {
            val repo = StubBillRepository(openCount = 3)
            val check = OpenBillsCheck(repo)

            val result = check.run(startLdt, endLdt)

            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, result.status)
            assertEquals(3, result.affectedCount)
            assertEquals(MonthlyCloseDto.SectionId.DISCREPANCIES, result.jumpTo)
            assertEquals(MonthlyCloseDetailCodes.OPEN_BILLS, result.detailCode)
            assertEquals(listOf("3"), result.detailArgs)
        }

    // ---------------------------------------------------------------------------
    // Minimal stub repository — only countOpenInWindow is relevant
    // ---------------------------------------------------------------------------

    private class StubBillRepository(
        private val openCount: Int,
    ) : BillRepository {
        override fun countOpenInWindow(
            start: LocalDateTime,
            end: LocalDateTime,
        ) = openCount

        override fun countUnsignedTseInWindow(
            start: LocalDateTime,
            end: LocalDateTime,
        ) = 0

        override fun existsClosedBillsBetween(
            start: LocalDateTime,
            end: LocalDateTime,
        ) = false

        override fun findAll() = emptyList<Bill>()

        override fun findById(billId: Long) = null

        override fun findByBillNumber(billNumber: String) = null

        override fun findByEmployee(employee: Employee) = emptyList<Bill>()

        override fun findByTableSessionId(tableSessionId: Long) = emptyList<Bill>()

        override fun findByDateRange(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
        ) = emptyList<Bill>()

        override fun findByTipRecipient(tipRecipient: TipRecipient) = emptyList<Bill>()

        override fun findByAmountRange(
            minAmount: BigDecimal,
            maxAmount: BigDecimal,
        ) = emptyList<Bill>()

        override fun findDeleted() = emptyList<Bill>()

        override fun create(
            employee: Employee,
            tableSession: TableSession,
            billAmount: BigDecimal,
            tips: BigDecimal,
            billNumber: String?,
            billSequence: Long?,
        ) = throw UnsupportedOperationException()

        override suspend fun count(includeDeleted: Boolean) = 0L

        override suspend fun countByEmployeeId(
            employeeId: String,
            includeDeleted: Boolean,
        ) = 0L

        override suspend fun countByDateRange(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            includeDeleted: Boolean,
        ) = 0L

        override suspend fun getTotalRevenue(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            includeDeleted: Boolean,
        ) = BigDecimal.ZERO

        override suspend fun getTotalTips(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            includeDeleted: Boolean,
        ) = BigDecimal.ZERO

        override fun findWithPagination(
            offset: Int,
            limit: Int,
            includeDeleted: Boolean,
        ) = emptyList<Bill>()

        override fun searchBills(
            employeeId: String?,
            tableSessionId: Long?,
            startDate: LocalDateTime?,
            endDate: LocalDateTime?,
            minAmount: BigDecimal?,
            maxAmount: BigDecimal?,
            tipRecipient: TipRecipient?,
            includeDeleted: Boolean,
            offset: Long,
            limit: Int?,
            includeTraining: Boolean,
        ) = emptyList<Bill>()

        override fun findByTseTransactionNumber(transactionNumber: Long) = null

        override suspend fun generatedBillNumber() = "RE-260501-1"
    }
}
