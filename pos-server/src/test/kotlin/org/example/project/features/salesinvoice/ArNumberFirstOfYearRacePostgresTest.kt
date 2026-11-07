package org.example.project.features.salesinvoice

import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.features.salesinvoice.database.tables.ArNumberSequenceTable
import org.example.project.testutils.PostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Two sales invoices that are the first of their year at the same moment both used to find no
 * `ar_number_sequence` row to lock, both INSERT one, and the loser died on
 * `ar_number_sequence_prefix_year_unique` — a 500 on issuance. Mirrors
 * `BillNumberConcurrencyPostgresTest` for the bill counter.
 *
 * Each year below starts with no row and is hit by several writers released together from a
 * barrier, under READ COMMITTED (the `dbQuery` default), with Exposed's own transaction retry
 * switched off (`maxAttempts = 1`) — a retry would otherwise paper over the duplicate-key failure
 * and let the old code pass. Postgres only: H2 runs one connection and never contends.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Concurrent first-of-year AR numbers: no duplicate key, no duplicate, no gap")
class ArNumberFirstOfYearRacePostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private lateinit var db: Database
    private val repo = ArNumberSequenceRepositoryImpl()

    @BeforeAll
    fun setUp() {
        pg = PostgresTestDatabase.start()
        db = Database.connect(pg.dataSource)
        transaction(db) { SchemaUtils.create(ArNumberSequenceTable) }
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    fun `writers racing for a year's first number all succeed with consecutive numbers`() {
        val writers = 6
        val years = 2030..2039
        val pool = Executors.newFixedThreadPool(writers)

        try {
            for (year in years) {
                val barrier = CyclicBarrier(writers)
                val tasks =
                    (1..writers).map {
                        Callable {
                            barrier.await(30, TimeUnit.SECONDS)
                            transaction(db, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                                maxAttempts = 1
                                val number = repo.nextNumber(prefix = "RG", year = year)
                                Thread.sleep(20) // hold the counter while the others queue on it
                                number
                            }
                        }
                    }
                val issued = pool.invokeAll(tasks).map { it.get(60, TimeUnit.SECONDS) }

                assertEquals(
                    (1..writers).map { "RG-$year-${it.toString().padStart(5, '0')}" },
                    issued.sorted(),
                    "year $year: every racing writer gets a distinct number, consecutive from 1",
                )
            }
        } finally {
            pool.shutdown()
        }
    }
}
