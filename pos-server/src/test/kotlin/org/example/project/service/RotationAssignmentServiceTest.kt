package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RotationAssignmentServiceTest {
    private val service: RotationAssignmentService =
        RotationAssignmentServiceImpl(
            employeeRepository = EmployeeRepositoryImpl(),
            configManager = ShopConfigurationManager,
            permissionGroups = PermissionGroupRepositoryImpl(),
            employeeInfoRepository = EmployeeInfoRepositoryImpl(),
        )

    private fun seedPoolEmployee(
        number: String,
        inPool: Boolean = true,
        active: Boolean = true,
    ): Employee {
        val emp =
            if (active) {
                TestData.createCashier(employeeNumber = number, username = "u_$number")
            } else {
                TestData.createInactiveEmployee(employeeNumber = number, username = "u_$number")
            }
        return transaction {
            val refreshed = Employee.findById(emp.id)!!
            refreshed.inRotationPool = inPool
            refreshed
        }
    }

    @Test
    fun `picks first employee when cursor is null`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { ShopConfigurationManager.setOrderRotationCursor(null) }

                val picked = runBlocking { service.pickNextEmployee() }
                assertEquals("001", picked.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `advances cursor and wraps`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { ShopConfigurationManager.setOrderRotationCursor(null) }

                val first = runBlocking { service.pickNextEmployee() }
                val second = runBlocking { service.pickNextEmployee() }
                val third = runBlocking { service.pickNextEmployee() }

                assertEquals("001", first.employeeNumber)
                assertEquals("002", second.employeeNumber)
                assertEquals("001", third.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `skips employees not in pool`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001", inPool = false)
                seedPoolEmployee("002", inPool = true)
                runBlocking { ShopConfigurationManager.setOrderRotationCursor(null) }

                val picked = runBlocking { service.pickNextEmployee() }
                assertEquals("002", picked.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `skips inactive employees`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001", inPool = true, active = false)
                seedPoolEmployee("002", inPool = true)
                runBlocking { ShopConfigurationManager.setOrderRotationCursor(null) }

                val picked = runBlocking { service.pickNextEmployee() }
                assertEquals("002", picked.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `throws when pool is empty`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001", inPool = false)
                runBlocking { ShopConfigurationManager.setOrderRotationCursor(null) }

                val ex =
                    assertThrows(PosException::class.java) {
                        runBlocking { service.pickNextEmployee() }
                    }
                assertEquals(PosErrorCode.ROTATION_POOL_EMPTY, ex.code)
                assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
            },
        ) { _ -> }
    }

    @Test
    fun `a terminal-role employee flagged in the pool is never picked`() {
        testApp(
            setupData = { TestData.createShopInfo() },
        ) { _ ->
            seedPoolEmployee("001")
            // Flagged directly on the row, bypassing EmployeeServiceImpl.setRotationPool (which refuses
            // it): the pool query itself must never deal a table to a terminal account.
            val terminal = TestData.createTerminalDeviceEmployee()
            transaction { Employee.findById(terminal.id)!!.inRotationPool = true }
            ShopConfigurationManager.setOrderRotationCursor(null)

            val first = service.pickNextEmployee()
            val second = service.pickNextEmployee()

            assertEquals("001", first.employeeNumber)
            assertEquals("001", second.employeeNumber, "the rotation wrapped onto the terminal account")
        }
    }

    @Test
    fun `throws ROTATION_POOL_EMPTY when the only flagged employee is the terminal device`() {
        testApp(
            setupData = { TestData.createShopInfo() },
        ) { _ ->
            val terminal = TestData.createTerminalDeviceEmployee()
            // Flagged directly on the row, bypassing EmployeeServiceImpl.setRotationPool (which refuses
            // it) — same as the "never picked" test above: the pool is then effectively empty of anyone
            // pickable, and the error detail must not claim otherwise.
            transaction { Employee.findById(terminal.id)!!.inRotationPool = true }
            ShopConfigurationManager.setOrderRotationCursor(null)

            TestData.withRoundRobinAssignment(true) {
                val ex = assertFailsWith<PosException> { service.pickNextEmployee() }
                assertEquals(PosErrorCode.ROTATION_POOL_EMPTY, ex.code)
                assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
            }
        }
    }

    @Test
    fun `a pick whose transaction rolls back leaves the cursor where the database has it`() {
        testApp(
            setupData = { TestData.createShopInfo() },
        ) { _ ->
            // T9-18 / checklist 8.5: the order path picks inside its own transaction and then refuses
            // (409 TRAINING_MODE_MISMATCH). The DB cursor rolled back, but the config cache kept the
            // advance, so the next table skipped the waiter who was due.
            seedPoolEmployee("001")
            seedPoolEmployee("002")
            ShopConfigurationManager.setOrderRotationCursor(null)
            val first = service.pickNextEmployee()
            assertEquals("001", first.employeeNumber)

            val refused =
                runCatching {
                    dbQuery {
                        assertEquals("002", service.pickNextEmployee().employeeNumber)
                        throw PosException(PosErrorCode.TRAINING_MODE_MISMATCH, HttpStatusCode.Conflict, "refused after the pick")
                    }
                }.exceptionOrNull()
            assertIs<PosException>(refused)

            assertEquals(first.id, ShopConfigurationManager.getOrderRotationCursor()?.toString(), "the cursor kept a rolled-back pick")
            assertEquals("002", service.pickNextEmployee().employeeNumber, "the waiter who was due was skipped")
        }
    }
}
