package org.example.project.features.employee

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.auth.data.repository.RefreshTokenRepositoryImpl
import org.example.project.features.auth.data.service.RefreshTokenServiceImpl
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.data.service.EmployeeInfoServiceImpl
import org.example.project.features.employee.data.service.EmployeeServiceImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.order.data.service.RotationStaffRosterListener
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FO-52 + FO-31: who the staff directory lists, and the HR name it carries.
 *
 * The directory is READ-ONLY with respect to hierarchy: letting an ADMIN list other admins must not
 * hand anyone a way to act on one, so the write guards on an admin target are pinned here too.
 */
@DisplayName("Staff directory: admin rows, display name, unchanged write guards")
class EmployeeDirectoryVisibilityTest {
    private val employees =
        EmployeeServiceImpl(
            EmployeeRepositoryImpl(),
            RefreshTokenServiceImpl(RefreshTokenRepositoryImpl()) { 0L },
            GroupCapabilityResolver(),
            PermissionGroupRepositoryImpl(),
            listOf(RotationStaffRosterListener()),
        )
    private val info = EmployeeInfoServiceImpl(EmployeeInfoRepositoryImpl(), EmployeeRepositoryImpl())

    private lateinit var admin: UUID
    private lateinit var otherAdmin: UUID
    private lateinit var manager: UUID
    private lateinit var cashier: UUID

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        admin = TestData.createAdmin().id.value
        otherAdmin = TestData.createAdmin(employeeNumber = "ADM002", username = "owner").id.value
        manager = TestData.createManager().id.value
        cashier = TestData.createCashier().id.value
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private val readEmployees = setOf(Capability.EMPLOYEE_READ)

    private fun summaries(
        requester: UUID,
        caps: Set<Capability> = readEmployees,
    ): List<EmployeeDto.EmployeeSummaryResponseDto> =
        runBlocking {
            employees.getEmployeeSummaries(requester, 100, 0, null, caps)
        }

    private fun capabilitiesOf(groupCode: String): Set<Capability> =
        runBlocking {
            dbQuery {
                PermissionGroupRepositoryImpl().findGroup(groupCode)!!.capabilities
            }
        }

    @Test
    fun `an admin lists admin rows too, themselves and other admins`() {
        val numbers = summaries(admin).map { it.employeeNumber }.toSet()

        assertTrue("ADM001" in numbers, "the admin must see their own row: $numbers")
        assertTrue("ADM002" in numbers, "the admin must see the other admin / owner row: $numbers")
        assertTrue("MGR001" in numbers && "CSH001" in numbers)
    }

    @Test
    fun `a manager still sees only cashier rows and never an admin or another manager`() {
        val numbers = summaries(manager).map { it.employeeNumber }.toSet()

        assertEquals(setOf("CSH001"), numbers)
    }

    @Test
    fun `displayName carries the HR first and last name for a caller holding EMPLOYEE_READ`() =
        runBlocking {
            info.createEmployeeInfo(EmployeeInfoDto.CreateEmployeeInfoRequestDto(cashier.toString(), "Klara", "Kern"))

            val rows = summaries(admin)
            val row = rows.single { it.employeeNumber == "CSH001" }

            assertEquals("Klara Kern", row.displayName)
            assertEquals("Klara Kern", row.shownName())
            val noRecord = rows.single { it.employeeNumber == "MGR001" }
            assertNull(noRecord.displayName, "no HR record: null")
            assertEquals("manager", noRecord.shownName())
        }

    @Test
    fun `displayName is null for a caller without EMPLOYEE_READ`() =
        runBlocking {
            info.createEmployeeInfo(EmployeeInfoDto.CreateEmployeeInfoRequestDto(cashier.toString(), "Klara", "Kern"))

            val rows = summaries(admin, caps = emptySet())

            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.displayName == null }, "HR names are personnel data: $rows")
        }

    @Test
    fun `a manager cannot change the role of an admin target - the write guard is untouched`() {
        val caps = capabilitiesOf("MANAGER")
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    employees.updateEmployee(
                        otherAdmin,
                        EmployeeDto.UpdateEmployeeRequestDto(role = "CASHIER"),
                        manager.toString(),
                        caps,
                    )
                }
            }
        assertEquals(403, ex.httpStatus.value)
    }

    @Test
    fun `an admin still cannot change their own role`() {
        val caps = capabilitiesOf("ADMIN")
        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    employees.updateEmployee(
                        admin,
                        EmployeeDto.UpdateEmployeeRequestDto(role = "MANAGER"),
                        admin.toString(),
                        caps,
                    )
                }
            }
        assertEquals(409, ex.httpStatus.value, "self role change must stay refused")
    }

    @Test
    fun `the last active admin still cannot be deactivated`() {
        runBlocking { employees.deactivateEmployee(otherAdmin, admin.toString()) }

        val ex = assertFailsWith<PosException> { runBlocking { employees.deactivateEmployee(admin, otherAdmin.toString()) } }

        assertTrue(ex.httpStatus.value in 400..409)
    }

    @Test
    fun `a manager is refused every write on an admin target`() {
        val caps = capabilitiesOf("MANAGER")
        val refusals =
            listOf<suspend () -> Any?>(
                { employees.updateEmployee(otherAdmin, EmployeeDto.UpdateEmployeeRequestDto(notes = "x"), manager.toString(), caps) },
                { employees.deactivateEmployee(otherAdmin, manager.toString()) },
                { employees.deleteEmployee(otherAdmin, manager.toString()) },
                { employees.assignPermissionGroup(otherAdmin, "CASHIER", manager) },
            )
        refusals.forEach { call ->
            val ex = assertFailsWith<PosException> { runBlocking { call() } }
            assertEquals(403, ex.httpStatus.value)
        }
    }

    @Test
    fun `an admin may still update and deactivate another admin`() {
        val caps = capabilitiesOf("ADMIN")
        runBlocking {
            employees.updateEmployee(otherAdmin, EmployeeDto.UpdateEmployeeRequestDto(notes = "ok"), admin.toString(), caps)
            employees.deactivateEmployee(otherAdmin, admin.toString())
        }
    }

    @Test
    fun `an invalid HR email message never echoes the address`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                EmployeeInfoDto.CreateEmployeeInfoRequestDto(cashier.toString(), "Klara", "Kern", email = "klara-private").validate()
            }
        assertTrue("klara-private" !in ex.message.orEmpty(), "the audit row copies this message: ${ex.message}")
    }
}
