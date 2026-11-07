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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The row lock is the only thing standing between two concurrent issuances and two invoices bearing
 * the same §14 UStG number. H2 in the default suite runs one connection, so it never contends and a
 * missing `forUpdate()` would pass unnoticed — this test is the one that would catch it.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Concurrent AR number issuance produces no duplicate and no gap")
class ArNumberSequenceConcurrencyTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = ArNumberSequenceRepositoryImpl()

    @BeforeAll
    fun setUp() {
        pg = PostgresTestDatabase.start()
        Database.connect(pg.dataSource)
        transaction { SchemaUtils.create(ArNumberSequenceTable) }
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    fun `fifty concurrent issuances yield fifty distinct consecutive numbers`() {
        val threads = 10
        val perThread = 5
        val pool = Executors.newFixedThreadPool(threads)

        val tasks =
            (1..threads).map {
                Callable {
                    (1..perThread).map {
                        transaction { repo.nextNumber(prefix = "RG", year = 2027) }
                    }
                }
            }

        val issued =
            try {
                pool.invokeAll(tasks).flatMap { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
            }

        assertEquals(threads * perThread, issued.size)
        assertEquals(issued.size, issued.toSet().size, "every number must be unique:\n${issued.sorted()}")
        assertEquals(
            (1..threads * perThread).map { "RG-2027-${it.toString().padStart(5, '0')}" },
            issued.sorted(),
            "the numbers must be consecutive from 1 — a gap is a §14 finding",
        )
    }
}
