package org.example.project.service

import com.zaxxer.hikari.HikariConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.database.DatabaseFactory
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNotEquals

/**
 * emu20 NOTE-r on real PostgreSQL: two station opens a second apart went to the same waiter.
 *
 * H2 cannot show this, because the danger is Postgres REPEATABLE READ — the isolation `createOrder`
 * runs its table open under. A FOR UPDATE row lock alone does not refresh a REPEATABLE READ snapshot;
 * what makes the locked cursor read safe is that the first pick genuinely UPDATED the row, so the
 * second transaction's `SELECT … FOR UPDATE` fails with a serialization error (40001) and
 * `withRetryThrows` re-runs it on a fresh snapshot that sees the committed cursor.
 *
 * The scenario, step by step, each pick in its own REPEATABLE READ transaction wrapped in the same
 * retry `createOrder` uses:
 *  1. both transactions take their snapshot (a first read) before either picks;
 *  2. A picks and stays uncommitted;
 *  3. another request reloads the shared configuration cache in its own transaction — which cannot
 *     see A's uncommitted cursor (the takeaway in the QA burst);
 *  4. B picks while A is still open; then A commits.
 *
 * Before the fix B read the cursor from that reloaded cache and handed out A's employee again.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Round-robin picks are distinct on real PostgreSQL (emu20 NOTE-r)")
class RotationPickPostgresConcurrencyTest {
    private lateinit var pg: PostgresTestDatabase

    private val service =
        RotationAssignmentServiceImpl(
            employeeRepository = EmployeeRepositoryImpl(),
            configManager = ShopConfigurationManager,
            permissionGroups = PermissionGroupRepositoryImpl(),
            employeeInfoRepository = EmployeeInfoRepositoryImpl(),
        )

    @BeforeAll
    fun startDatabase() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        if (DatabaseFactory.isInitialized()) DatabaseFactory.close()
        initDB(
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                driverClassName = "org.postgresql.Driver"
                username = "postgres"
                password = "postgres"
                maximumPoolSize = 8
                isAutoCommit = false
            },
            runMigrations = false,
        )
        startKoin { modules(module { single { ConfigurationRepository() } }) }

        TestData.createShopInfo()
        (1..3).forEach { n ->
            val employee = TestData.createCashier(employeeNumber = "K00$n", username = "pool$n")
            transaction { Employee.findById(employee.id)!!.inRotationPool = true }
        }
    }

    @AfterAll
    fun stopDatabase() {
        stopKoin()
        DatabaseFactory.close()
        pg.close()
    }

    /** One table open's transaction: snapshot first, as `createOrder` reads config before it picks. */
    private suspend fun openTable(
        snapshotsTaken: CountDownLatch,
        beforePick: CompletableDeferred<Unit>?,
        afterPick: CompletableDeferred<Unit>?,
        beforeCommit: CompletableDeferred<Unit>?,
    ): String =
        withRetryThrows(operationName = "rotationPickPostgresTest", config = RetryConfig.AGGRESSIVE) {
            dbQuery(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
                ShopInfo.all().count()
                snapshotsTaken.countDown()
                snapshotsTaken.await()
                beforePick?.await()
                val picked = service.pickNextEmployee().employeeNumber
                afterPick?.complete(Unit)
                beforeCommit?.await()
                picked
            }
        }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `two concurrent table opens with a cache reload in between pick different employees`() =
        runBlocking {
            ShopConfigurationManager.setOrderRotationCursor(null)
            ShopConfigurationManager.refreshConfiguration()

            val snapshotsTaken = CountDownLatch(2)
            val aPicked = CompletableDeferred<Unit>()
            val staleReloadDone = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()

            withTimeout(60_000) {
                val a = GlobalScope.async(Dispatchers.IO) { openTable(snapshotsTaken, null, aPicked, releaseA) }
                val b = GlobalScope.async(Dispatchers.IO) { openTable(snapshotsTaken, staleReloadDone, null, null) }

                aPicked.await()
                // Another request reloads the shared config in its own transaction while A is open.
                GlobalScope.async(Dispatchers.IO) { ShopConfigurationManager.refreshConfiguration() }.await()
                staleReloadDone.complete(Unit)
                // Let B reach the cursor (blocked on A's row lock, or done reading a stale cache).
                delay(1_500)
                releaseA.complete(Unit)

                val first = a.await()
                val second = b.await()
                assertNotEquals(first, second, "both table opens went to $first")
            }
        }
}
