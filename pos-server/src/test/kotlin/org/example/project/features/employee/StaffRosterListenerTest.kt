package org.example.project.features.employee

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.auth.data.repository.RefreshTokenRepositoryImpl
import org.example.project.features.auth.data.service.RefreshTokenServiceImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.data.service.EmployeeServiceImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.domain.service.StaffRosterListener
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Staff changes notify every registered [StaffRosterListener] once, after commit, and never on rollback. */
@DisplayName("Employee service notifies roster listeners")
class StaffRosterListenerTest {
    private val first = AtomicInteger()
    private val second = AtomicInteger()

    private val employees =
        EmployeeServiceImpl(
            EmployeeRepositoryImpl(),
            RefreshTokenServiceImpl(RefreshTokenRepositoryImpl()) { 0L },
            GroupCapabilityResolver(),
            PermissionGroupRepositoryImpl(),
            listOf(StaffRosterListener { first.incrementAndGet() }, StaffRosterListener { second.incrementAndGet() }),
        )

    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun assertNotified(expected: Int) {
        assertEquals(expected, first.get(), "first listener")
        assertEquals(expected, second.get(), "second listener")
    }

    @Test
    fun `test_employee_status_change_notifies_roster_listeners`() =
        runBlocking {
            val actor = TestData.createManager()
            val a = TestData.createCashier(employeeNumber = "SRL_A", username = "srl_a")
            val b = TestData.createCashier(employeeNumber = "SRL_B", username = "srl_b")
            val c = TestData.createCashier(employeeNumber = "SRL_C", username = "srl_c")
            val d = TestData.createCashier(employeeNumber = "SRL_D", username = "srl_d")

            employees.updateEmployee(
                employeeId = a.id.value,
                updateData = EmployeeDto.UpdateEmployeeRequestDto(employmentStatus = "INACTIVE"),
                userId = actor.id.value.toString(),
                actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
            )
            assertNotified(1)

            employees.changeEmploymentStatus(b.id.value, EmploymentStatus.INACTIVE, null)
            assertNotified(2)

            employees.deleteEmployee(c.id.value, userId = actor.id.value.toString())
            assertNotified(3)

            employees.setRotationPool(d.id.value, enabled = true)
            assertNotified(4)
        }

    @Test
    fun `an edit that moves neither status nor role notifies nobody`() =
        runBlocking {
            val actor = TestData.createManager()
            val emp = TestData.createCashier(employeeNumber = "SRL_N", username = "srl_n")

            employees.updateEmployee(
                employeeId = emp.id.value,
                updateData = EmployeeDto.UpdateEmployeeRequestDto(notes = "just a note"),
                userId = actor.id.value.toString(),
                actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
            )

            assertNotified(0)
        }

    @Test
    fun `a rolled back update or delete notifies nobody`() =
        runBlocking {
            val emp = TestData.createCashier(employeeNumber = "SRL_R", username = "srl_r")

            assertFails {
                employees.updateEmployee(
                    employeeId = emp.id.value,
                    updateData = EmployeeDto.UpdateEmployeeRequestDto(employmentStatus = "INACTIVE"),
                    userId = "not-a-uuid",
                    actorCapabilities = setOf(Capability.EMPLOYEE_WRITE),
                )
            }
            assertFails { employees.deleteEmployee(emp.id.value, userId = "not-a-uuid") }

            assertNotified(0)
        }
}
