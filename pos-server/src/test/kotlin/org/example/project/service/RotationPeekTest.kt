package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.data.service.RotationAssignmentServiceImpl
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RotationPeekTest {
    private val service: RotationAssignmentService =
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
    fun `peek returns first when cursor unset`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { TestData.setRoundRobinAssignment(true) }

                val peeked = runBlocking { service.peekNext() }
                assertEquals("001", peeked?.employeeNumber)
                assertNull(peeked?.displayName, "no employee_info row -> no display name")
            },
        ) { _ -> }
    }

    @Test
    fun `peek does not advance cursor`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                seedPoolEmployee("002")
                runBlocking { TestData.setRoundRobinAssignment(true) }

                val first = runBlocking { service.peekNext() }
                val again = runBlocking { service.peekNext() }
                assertEquals(first, again)

                val picked = runBlocking { service.pickNextEmployee() }
                assertEquals(first?.employeeNumber, picked.employeeNumber)

                val following = runBlocking { service.peekNext() }
                assertEquals("002", following?.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `peek is null when toggle off`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(false) }

                assertNull(runBlocking { service.peekNext() })
            },
        ) { _ -> }
    }

    @Test
    fun `peek is null when pool empty`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                runBlocking { TestData.setRoundRobinAssignment(true) }

                assertNull(runBlocking { service.peekNext() })
            },
        ) { _ -> }
    }

    @Test
    fun `peek restarts at first when cursor employee left pool`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                val a = seedPoolEmployee("001")
                seedPoolEmployee("002")
                seedPoolEmployee("003")
                runBlocking { TestData.setRoundRobinAssignment(true) }

                val picked = runBlocking { service.pickNextEmployee() }
                assertEquals("001", picked.employeeNumber)
                transaction { Employee.findById(a.id)!!.inRotationPool = false }

                assertEquals("002", runBlocking { service.peekNext() }?.employeeNumber)
            },
        ) { _ -> }
    }

    @Test
    fun `peek creates no cursor row`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                seedPoolEmployee("001")
                runBlocking { TestData.setRoundRobinAssignment(true) }
                transaction { ShopConfigurationTable.deleteWhere { ShopConfigurationTable.key eq ConfigKey.ORDER_ROTATION_CURSOR.key } }

                assertEquals("001", runBlocking { service.peekNext() }?.employeeNumber)

                val rows =
                    transaction {
                        ShopConfigurationTable
                            .selectAll()
                            .where { ShopConfigurationTable.key eq ConfigKey.ORDER_ROTATION_CURSOR.key }
                            .count()
                    }
                assertEquals(0L, rows, "peek must not create the cursor row")
            },
        ) { _ -> }
    }
}
