package org.example.project.features.bill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toJavaLocalDate
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.repository.BillRepositoryImpl
import org.example.project.features.bill.data.service.BillWriteConflict
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.math.BigDecimal
import java.sql.Connection
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

/**
 * Parallel bills from one till must get distinct, contiguous numbers (real-TSE QA rt12, finding d:
 * five parallel bills → four 201s and one 500 on a duplicate `RE-260506-39`).
 *
 * Postgres only, on purpose: H2 runs the default suite on one connection and never contends, so a
 * missing `forUpdate()` — or the old `count(*)` — passes there. Each writer reproduces the shape of
 * `BillServiceImpl.createBill`'s phase 1: a REPEATABLE_READ transaction that has already READ
 * (its snapshot is fixed before the number is drawn, which is what made the old count stale), then
 * draws the number, holds the transaction open a moment, and inserts the bill under the
 * `bill_bill_number_unique` constraint — wrapped in the same [BillWriteConflict.retrying] the
 * service uses. The service itself is not reachable on Postgres (`testApp` is H2-only).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Concurrent bill creation yields distinct, contiguous bill numbers on PostgreSQL")
class BillNumberConcurrencyPostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = BillRepositoryImpl()
    private var sessionId = 0L

    @BeforeAll
    fun startDatabase() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        if (DatabaseFactory.isInitialized()) DatabaseFactory.close()
        initDB(pg.hikariConfig().apply { maximumPoolSize = 10 }, runMigrations = false)
        sessionId =
            TestData
                .createTableWithSession("PAR1")
                .session.id.value
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.close()
    }

    @Test
    fun `eight parallel bills get eight distinct numbers with no gap`() {
        val writers = 8
        val start = CompletableDeferred<Unit>()

        val issued =
            runBlocking {
                val calls =
                    (1..writers).map { n ->
                        async(Dispatchers.IO) {
                            start.await()
                            BillWriteConflict.retrying("parallel bill $n") {
                                dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                                    // Fix the snapshot BEFORE the number is drawn, as createBill does.
                                    Bill.count()
                                    val number = repo.generatedBillNumber()
                                    Thread.sleep(40) // keep the counter row locked while others queue
                                    insertBill(number)
                                    number
                                }
                            }
                        }
                    }
                start.complete(Unit)
                withTimeout(120_000) { calls.awaitAll() }
            }

        val today = currentTimeStamp().date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("yyMMdd"))
        assertEquals(writers, issued.toSet().size, "every bill number must be unique: ${issued.sorted()}")
        assertEquals(
            (0 until writers).map { "RE-$today-$it" },
            issued.sortedBy { it.substringAfterLast('-').toLong() },
            "the month's numbers must be contiguous — a hole is an audit question",
        )
        val stored = runBlocking { dbQuery { Bill.all().map { it.billNumber }.toSet() } }
        assertEquals(issued.toSet(), stored, "every issued number belongs to a committed bill")
    }

    private fun insertBill(number: String) {
        val session = TableSession.findById(sessionId)!!
        Bill.new {
            this.employeeId = session.employeeId
            this.tableSession = session
            this.billAmount = BigDecimal.ONE
            this.paidAmount = BigDecimal.ONE
            this.tips = BigDecimal.ZERO
            this.startTime = session.startTime
            this.endTime = currentTimeStamp()
            this.billNumber = number
        }
    }
}
