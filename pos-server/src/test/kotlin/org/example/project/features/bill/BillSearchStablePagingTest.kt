package org.example.project.features.bill

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.features.bill.data.repository.BillRepositoryImpl
import org.example.project.features.bill.domain.models.Bill
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The Z-Bon "Rechnungen" tab walks `/bills/search` page by page (`searchAllReceipts`). Ordered by
 * `endTime` alone, bills closed in the same second came back in an arbitrary order per query, so a
 * page boundary could skip one bill and repeat another. The id now breaks the tie, and offset/limit
 * run in SQL against that same total order.
 */
@DisplayName("Bill search pages are stable when endTime ties")
class BillSearchStablePagingTest {
    private val repository = BillRepositoryImpl()
    private val closedAt = LocalDate(2026, 10, 1).atTime(21, 0, 0)

    @Test
    fun `paging over identical endTimes returns every bill exactly once`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val tws = TestData.createTableWithSession("PAGE")
                transaction {
                    repeat(BILL_COUNT) { i ->
                        Bill.new {
                            this.employeeId = tws.session.employeeId
                            this.tableSession = tws.session
                            this.billNumber = "PAGE-$i"
                            this.billAmount = BigDecimal("10.0000")
                            this.paidAmount = BigDecimal("10.0000")
                            this.startTime = closedAt
                            this.endTime = closedAt
                            this.isTraining = false
                        }
                    }
                }
            },
        ) { _ ->
            transaction {
                val all = repository.searchBills(includeDeleted = true).map { it.id.value }
                assertEquals(BILL_COUNT, all.size)
                assertEquals(all.sortedDescending(), all, "ties on endTime are ordered by id, newest first")

                val paged =
                    (0 until BILL_COUNT step PAGE_SIZE).flatMap { offset ->
                        repository
                            .searchBills(includeDeleted = true, offset = offset.toLong(), limit = PAGE_SIZE)
                            .map { it.id.value }
                    }
                assertEquals(all, paged, "the pages concatenate to the unpaged result: no bill skipped or repeated")
                assertEquals(BILL_COUNT, paged.toSet().size)

                assertEquals(
                    emptyList(),
                    repository.searchBills(includeDeleted = true, offset = BILL_COUNT.toLong(), limit = PAGE_SIZE),
                    "the page after the last bill is empty, which ends the client's walk",
                )
            }
        }

    private companion object {
        const val BILL_COUNT = 23
        const val PAGE_SIZE = 5
    }
}
