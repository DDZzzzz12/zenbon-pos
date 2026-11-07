package org.example.project.features.order

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.project.database.DatabaseFactory
import org.example.project.features.order.data.repository.OrderRepositoryImpl
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.sql.Connection
import kotlin.test.assertEquals

/**
 * Parallel orders from several tills must get distinct, contiguous numbers (`POST /api/orders`
 * answered 500 on a duplicate `ORD-20261008-7` once in ~14k orders under a 3-cashier stress loop).
 *
 * Postgres only, on purpose: H2 runs the default suite on one connection and never contends, so a
 * missing `forUpdate()` — or the old `max(id) + 1` — passes there. Each writer reproduces the shape
 * of `OrderServiceImpl.createOrder`'s phase 1: a REPEATABLE_READ transaction that has already READ
 * (its snapshot is fixed before the number is drawn, which is what made the old maximum stale),
 * draws the number, holds the transaction open a moment and commits under the
 * `t_order_order_number_unique` constraint — wrapped in the same `withRetryThrows(AGGRESSIVE)` the
 * service uses. The service itself is not reachable on Postgres (`testApp` is H2-only).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Concurrent order creation yields distinct, contiguous order numbers on PostgreSQL")
class OrderNumberConcurrencyPostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = OrderRepositoryImpl()
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
    fun `parallel cashiers creating orders never draw the same number`() {
        val cashiers = 3
        val ordersEach = 15
        val start = CompletableDeferred<Unit>()

        val issued =
            runBlocking {
                val calls =
                    (1..cashiers).map { cashier ->
                        async(Dispatchers.IO) {
                            start.await()
                            List(ordersEach) { n ->
                                withRetryThrows("parallel order $cashier/$n", RetryConfig.AGGRESSIVE) {
                                    dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                                        // Fix the snapshot BEFORE the number is drawn, as createOrder does.
                                        Orders.count()
                                        val session = TableSession.findById(sessionId)!!
                                        val order = repo.create(employee = session.employeeId, tableSession = session)
                                        Thread.sleep(10) // keep the counter row locked while others queue
                                        order.orderNumber
                                    }
                                }
                            }
                        }
                    }
                start.complete(Unit)
                withTimeout(120_000) { calls.awaitAll() }.flatten()
            }

        val total = cashiers * ordersEach
        val numbers = issued.map { it.substringAfterLast('-').toLong() }.sorted()
        assertEquals(total, issued.toSet().size, "every order number must be unique: ${issued.sorted()}")
        assertEquals(
            (numbers.first()..numbers.last()).toList(),
            numbers,
            "the numbers must be contiguous — a committed order never loses its number",
        )
        val stored = runBlocking { dbQuery { Orders.all().map { it.orderNumber }.toSet() } }
        assertEquals(issued.toSet(), stored, "every issued number belongs to a committed order")
    }
}
