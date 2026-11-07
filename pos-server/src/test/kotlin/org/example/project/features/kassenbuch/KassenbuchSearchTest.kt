package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [KassenbuchRepositoryImpl.searchEntries] — the "search all periods" escape the adminApp
 * Kassenbuch screen offers when the operator does not know which period an entry sits in.
 *
 * The shop-scoping test is the one that matters most: this is a brand-new query, and this branch
 * already had to close two cross-shop leaks (`getCurrentBalance` and the Z-Bon close lookup).
 */
class KassenbuchSearchTest {
    private lateinit var repo: KassenbuchRepositoryImpl
    private lateinit var books: CashBookRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        books = CashBookRepositoryImpl()
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun seed(
        targetShop: ShopInfo,
        bookId: Long,
        belegNr: String,
        belegtext: String,
    ): Long =
        transaction {
            repo
                .createEntry(
                    shop = targetShop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_DEPOSIT,
                            belegNr = belegNr,
                            vorzBetrag = "100.00",
                            belegDatum = "15.03.2026",
                            belegtext = belegtext,
                            cashBookId = bookId,
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.00", ustSatz = null)),
                    tseTransaction = null,
                ).id.value
        }

    /** Backdates [headerId]'s fiscal timestamp so the date-bounded query cannot see it. */
    private fun backdate(
        headerId: Long,
        at: LocalDateTime,
    ) = transaction {
        KassenbuchHeader.findById(headerId)!!.createdAt = at
    }

    @Test
    fun `searchEntries finds a belegNr across all periods`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val id = seed(shop, kasse, "2026-03-0042", "Alte Buchung")
        backdate(id, LocalDateTime(2026, 3, 15, 10, 0, 0))

        // Contrast: the date-bounded history query genuinely cannot see it any more, so the
        // search below is proving "no date bounds", not just "the row exists".
        val bounded = transaction { repo.getEntriesByDateRange(shop, "2026-08-01", "2026-08-31") }
        assertTrue(bounded.none { it.id.value == id }, "fixture is wrong: the entry is still inside the window")

        val found = transaction { repo.searchEntries(shop, "2026-03-0042") }
        assertEquals(listOf(id), found.map { it.id.value })
    }

    @Test
    fun `searchEntries matches belegtext case-insensitively`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val id = seed(shop, kasse, "2026-08-0001", "Metro Wareneinkauf")

        val found = transaction { repo.searchEntries(shop, "metro") }
        assertEquals(listOf(id), found.map { it.id.value })
    }

    @Test
    fun `searchEntries is scoped to the shop`() {
        val otherShop = TestData.createShopInfo(shopName = "Other Shop")
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val otherKasse = transaction { books.fiscalBookFor(otherShop).id.value }

        val mine = seed(shop, kasse, "2026-08-0001", "Metro Wareneinkauf")
        seed(otherShop, otherKasse, "2026-08-0001", "Metro Wareneinkauf")

        val found = transaction { repo.searchEntries(shop, "Metro") }
        assertEquals(listOf(mine), found.map { it.id.value })
    }

    @Test
    fun `searchEntries honours the cashBookIds filter`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        val tresor = transaction { books.create(shop, "Tresor", false, "1010").id.value }

        seed(shop, kasse, "2026-08-0001", "Metro Wareneinkauf")
        val inTresor = seed(shop, tresor, "2026-08-0002", "Metro Wareneinkauf")

        val found = transaction { repo.searchEntries(shop, "Metro", cashBookIds = listOf(tresor)) }
        assertEquals(listOf(inTresor), found.map { it.id.value })
    }

    @Test
    fun `searchEntries caps the result count at limit`() {
        val kasse = transaction { books.fiscalBookFor(shop).id.value }
        repeat(5) { seed(shop, kasse, "2026-08-000$it", "Metro Wareneinkauf") }

        val found = transaction { repo.searchEntries(shop, "Metro", limit = 3) }
        assertEquals(3, found.size)
    }
}
