package org.example.project.service

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RotationAssignmentConcurrencyTest {
    private val service =
        RotationAssignmentServiceImpl(
            employeeRepository = EmployeeRepositoryImpl(),
            configManager = ShopConfigurationManager,
            permissionGroups = PermissionGroupRepositoryImpl(),
            employeeInfoRepository = EmployeeInfoRepositoryImpl(),
        )

    private fun seedPoolEmployee(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        return transaction {
            val refreshed = Employee.findById(emp.id)!!
            refreshed.inRotationPool = true
            refreshed
        }
    }

    @Test
    fun `parallel picks over a full pool assign distinct employees`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                val poolSize = 8
                (1..poolSize).forEach { seedPoolEmployee("%03d".format(it)) }

                runBlocking {
                    ShopConfigurationManager.setOrderRotationCursor(null)
                    ShopConfigurationManager.refreshConfiguration()

                    // poolSize parallel picks. Without serialization, concurrent reads of the
                    // cached cursor hand the same employee to multiple callers (duplicates).
                    // With the mutex, each pick advances the cursor exactly once -> all distinct.
                    val picked =
                        coroutineScope {
                            (1..poolSize).map { async { service.pickNextEmployee() } }.awaitAll()
                        }

                    val distinct = picked.map { it.employeeNumber }.toSet()
                    assertEquals(poolSize, distinct.size)
                }
            },
        ) { _ -> }
    }

    /**
     * emu20 NOTE-r: two station opens in the same second both went to the same waiter. The pick
     * wrote its (still uncommitted) cursor into the shared configuration cache, and an unrelated
     * request — a takeaway in the QA burst — reloaded that cache in its own transaction, which could
     * not see the uncommitted write and so put the OLD cursor back. The next pick then handed out the
     * same employee. Reproduced here deterministically: the stale reload runs in a separate
     * transaction while the first pick's transaction is still open.
     */
    @Test
    fun `a cache reload from another transaction during a pick cannot hand out the same employee twice`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                (1..3).forEach { seedPoolEmployee("%03d".format(it)) }
            },
        ) { _ ->
            runBlocking {
                ShopConfigurationManager.setOrderRotationCursor(null)

                val first =
                    dbQuery {
                        val picked = service.pickNextEmployee()
                        // Another request's transaction reloads the shared config while this pick is
                        // uncommitted. GlobalScope: it must NOT join this coroutine's transaction.
                        @OptIn(DelicateCoroutinesApi::class)
                        GlobalScope.async(Dispatchers.IO) { ShopConfigurationManager.refreshConfiguration() }.await()
                        picked
                    }

                val second = service.pickNextEmployee()
                assertNotEquals(first.employeeNumber, second.employeeNumber, "the second open went to the same waiter")
            }
        }
    }
}
