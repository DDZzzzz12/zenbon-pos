package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDate
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.CashBookCloseStatusResolver
import org.example.project.features.kassenbuch.domain.CloseKind
import org.example.project.features.kassenbuch.domain.models.CashBookClose
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Task 23 — the unified "when was this cash book last closed?" read model.
 *
 * Read-only: no test here writes through the resolver, only through fixtures it then reads back.
 */
class CashBookCloseStatusTest {
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        books = CashBookRepositoryImpl()
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun seedKassensturz(
        bookId: Long,
        periodEnd: LocalDate,
        bookBalance: String = "500.00",
        countedBalance: String = "495.00",
    ): CashBookClose =
        transaction {
            CashBookClose.new {
                this.cashBook = books.findById(bookId)!!
                this.periodStart = LocalDate(periodEnd.year, periodEnd.monthNumber, 1)
                this.periodEnd = periodEnd
                this.bookBalance = BigDecimal(bookBalance)
                this.countedBalance = BigDecimal(countedBalance)
                this.difference = BigDecimal(countedBalance).subtract(BigDecimal(bookBalance))
                this.closedBy = employee
            }
        }

    @Test
    fun `lastClose reads the Z-Bon for a fiscal book`() {
        val fiscalBookId = transaction { books.fiscalBookFor(shop).id.value }
        val zBonEndTime =
            transaction {
                val zBon = TestData.createZBonFullDay(shop, employee)
                zBon.closingBalance = BigDecimal("321.50")
                zBon.endTime
            }

        val result = transaction { CashBookCloseStatusResolver.lastClose(fiscalBookId) }

        assertNotNull(result, "a fiscal book with a Z-Bon must resolve a close status")
        assertEquals(CloseKind.Z_BON, result.closeKind, "the fiscal book must be closed via Z_BON")
        assertEquals(zBonEndTime, result.closedAt, "closedAt must be the Z-Bon's endTime")
        assertNull(
            result.countedBalance,
            "a Z-Bon computes its closing balance; nothing was physically counted",
        )
    }

    @Test
    fun `lastClose reads cash_book_close for a non-fiscal book`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        seedKassensturz(tresor, periodEnd = LocalDate(2026, 7, 31), countedBalance = "495.00")

        val result = transaction { CashBookCloseStatusResolver.lastClose(tresor) }

        assertNotNull(result, "a non-fiscal book with a Kassensturz must resolve a close status")
        assertEquals(CloseKind.KASSENSTURZ, result.closeKind, "a Tresor is closed via KASSENSTURZ")
        assertNotNull(result.countedBalance, "a Kassensturz always records a counted balance")
        assertEquals(
            0,
            BigDecimal("495.00").compareTo(result.countedBalance),
            "countedBalance must be the count recorded on the CashBookClose row",
        )
    }

    @Test
    fun `lastClose is null for a book that has never been closed`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        val result = transaction { CashBookCloseStatusResolver.lastClose(tresor) }

        assertNull(result, "a book with no Kassensturz and no Z-Bon must resolve to no close")
    }

    @Test
    fun `openPeriod starts the day after the last close`() {
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }
        val periodEnd = LocalDate(2026, 7, 31)
        seedKassensturz(tresor, periodEnd = periodEnd)

        val range = transaction { CashBookCloseStatusResolver.openPeriod(tresor) }

        assertNotNull(range, "a closed book must have an open period")
        assertEquals(
            LocalDate(2026, 8, 1),
            range.start,
            "the open period must start the day AFTER the last close's periodEnd",
        )
        assertEquals(
            currentTimeStamp().date,
            range.endInclusive,
            "the open period must run through today",
        )
    }
}
