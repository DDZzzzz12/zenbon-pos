package org.example.project.features.supplier

import kotlinx.datetime.LocalDateTime
import org.example.project.database.DatabaseFactory
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.assertEquals

private val YEAR_START = LocalDateTime(2026, 1, 1, 0, 0)

/**
 * The aggregate ordering, on the engine that actually ships.
 *
 * `SupplierAggregateSortTest` proves the same rules on H2, and that is not enough on its own here:
 * the thing under test is where SQL puts a NULL, and H2 and PostgreSQL choose differently when left
 * to themselves — PostgreSQL sorts NULLs *last* ascending, H2 sorts them *first*. A supplier we owe
 * nothing produces `SUM(...) = NULL`, so on the default ordering the two engines would disagree
 * about whether it heads or tails a list ordered by what is outstanding.
 *
 * The repository states the placement explicitly instead of relying on either default. This is the
 * test that shows the statement is what the engine obeys, rather than H2's default happening to
 * match what was wanted.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Supplier aggregate ordering — real PostgreSQL (zonky)")
class SupplierAggregateSortPostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = SupplierRepositoryImpl()

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        initDB(pg.hikariConfig(), runMigrations = true)
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) {
            DatabaseFactory.close()
            pg.close()
        }
    }

    private fun seed() {
        transaction {
            // A creditor's party is shop-scoped, so the shop has to exist before the first
            // `repo.create`. Flyway builds the schema here; it seeds no rows.
            TestData.createShopInfo()
            val owed = repo.create(name = "Wir schulden", vatId = null)
            Invoice.new {
                supplier = owed
                invoiceNumber = "RE-1"
                openAmount = BigDecimal("250.00")
                createdAt = currentTimeStamp()
            }
            // No invoice at all, so its SUM is NULL rather than zero.
            repo.create(name = "Nichts offen", vatId = null)
        }
    }

    @Test
    fun `a supplier with nothing outstanding sorts as the smallest amount, not wherever NULL falls`() {
        seed()

        transaction {
            assertEquals(
                listOf("Nichts offen", "Wir schulden"),
                repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = true, ytdSince = YEAR_START).map { it.partner.name },
                "ascending: nothing owed is the least owed",
            )
            assertEquals(
                listOf("Wir schulden", "Nichts offen"),
                repo.list(sort = SupplierSort.OPEN_TOTAL, ascending = false, ytdSince = YEAR_START).map { it.partner.name },
                "descending: the largest debt heads the list",
            )
        }
    }

    /** Ordering by an aggregate must not drop the rows that have none of it. */
    @Test
    fun `both suppliers are returned whichever aggregate is ordered by`() {
        transaction {
            assertEquals(2, repo.list(sort = SupplierSort.OPEN_TOTAL, ytdSince = YEAR_START).size)
            assertEquals(2, repo.list(sort = SupplierSort.YTD_SPEND, ytdSince = YEAR_START).size)
        }
    }
}
