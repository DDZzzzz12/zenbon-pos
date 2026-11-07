package org.example.project.features.employee

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.mapper.toEmployeeResponse
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stored `employees.role` this build no longer spells must not fail the read — and must not gain
 * anything by becoming readable.
 *
 * The defect: `EmployeeTable.role` mapped the column with `enumerationByName`, whose `valueFromDB`
 * throws on an unmatched string. Two retired spellings are still reachable — `TERMINAL` (renamed by
 * V79) and `BASIC_EMPLOYEE` (renamed to `TERMINAL` by V42). In-place upgrades were rewritten by
 * those migrations; a **restore** reloads the archive's rows *after* migrations have run, so
 * neither rewrite reaches a pre-V42/pre-V79 backup. The same hole as the `PRINTERLOCATION` row that
 * 500'd the whole Änderungen screen — but on a column nothing filters out of a query, so one stale
 * row failed login, the staff list, the employee statistics and the `personal` custom export at
 * once.
 *
 * `employees.role` is current state near authorization, so the fix carries a second obligation the
 * `entry_log` one did not: the fallback must never resolve to **more** capability than the row had.
 * The last three tests are that half — capabilities unchanged, none when ungrouped, and no
 * promotion to the shared login-less device principal.
 *
 * H2 leg (`:pos-server:test`) on purpose: the value is a plain `VARCHAR(50)` on both engines, so
 * there is nothing dialect-specific to hide behind `@Tag("postgres")` — which would not run here.
 */
@DisplayName("Legacy employees.role values stay readable and stay powerless")
class EmployeeLegacyRoleValueTest {
    private val repository = EmployeeRepositoryImpl()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    /**
     * Rewrites one row's role to a string no enum constant matches.
     *
     * Raw SQL is the only way to reach the fallback: every value writable through the typed column
     * is by definition a name this build knows. An UPDATE rather than an INSERT because it models
     * the real shape exactly — the employee, their group assignment and their credentials all
     * exist; only the spelling of the role is stale, which is what a restored archive leaves behind.
     */
    private fun stampLegacyRole(
        employee: Employee,
        legacy: String,
    ) = transaction {
        // See EntryLogUnknownEnumValueTest: `role` is a column Exposed quotes, and H2 folds an
        // unquoted identifier to upper case, so a literal `role` here misses it.
        exec(
            "UPDATE ${EmployeeTable.tableName} SET ${identity(EmployeeTable.role)} = '$legacy' " +
                "WHERE ${identity(EmployeeTable.id)} = '${employee.id.value}'",
        )
    }

    private fun removeGroup(employeeId: UUID) =
        transaction {
            EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
        }

    private suspend fun roleOf(id: UUID): EmployeeRole = dbQuery { Employee.findById(id)!!.role }

    @Test
    fun `a pre-V79 TERMINAL row reads back instead of throwing`() =
        runBlocking {
            val employee = TestData.createCashier(employeeNumber = "LEG001", username = "legacy-terminal")
            stampLegacyRole(employee, "TERMINAL")

            assertEquals(EmployeeRole.TERMINAL_DEVICE, roleOf(employee.id.value))
        }

    @Test
    fun `a pre-V42 BASIC_EMPLOYEE row reads back instead of throwing`() =
        runBlocking {
            // The case `RestoreRepairs.normalizeLegacyTerminalRole` deliberately does NOT rewrite.
            val employee = TestData.createCashier(employeeNumber = "LEG002", username = "legacy-basic")
            stampLegacyRole(employee, "BASIC_EMPLOYEE")

            assertEquals(EmployeeRole.TERMINAL_DEVICE, roleOf(employee.id.value))
        }

    @Test
    fun `a spelling from a future rename degrades instead of throwing`() =
        runBlocking {
            // The durable half: the two above are the spellings we know about; this is the next one.
            val employee = TestData.createCashier(employeeNumber = "LEG003", username = "legacy-future")
            stampLegacyRole(employee, "SOME_FUTURE_RENAME")

            assertEquals(EmployeeRole.TERMINAL_DEVICE, roleOf(employee.id.value))
        }

    @Test
    fun `one stale row does not fail the whole staff list`() =
        runBlocking {
            // The actual outage shape: `getAllEmployees` has no role filter, so a single unreadable
            // row took down every colleague listed beside it.
            TestData.createCashier(employeeNumber = "LEG010", username = "legacy-neighbour")
            stampLegacyRole(TestData.createCashier(employeeNumber = "LEG011", username = "legacy-basic"), "BASIC_EMPLOYEE")
            stampLegacyRole(TestData.createManager(employeeNumber = "LEG012", username = "legacy-terminal"), "TERMINAL")

            val numbers = dbQuery { repository.getAllEmployees(100, 0, null).map { it.employeeNumber } }

            assertTrue(numbers.containsAll(listOf("LEG010", "LEG011", "LEG012")), "got $numbers")
        }

    @Test
    fun `the login mapper renders a stale row instead of failing the login`() =
        runBlocking {
            // `EmployeeMapper` reads `role.name`; before the fix this threw, so the employee could
            // not authenticate at all — and neither could anyone whose staff list included them.
            val employee = TestData.createCashier(employeeNumber = "LEG020", username = "legacy-basic")
            stampLegacyRole(employee, "BASIC_EMPLOYEE")

            val dto = dbQuery { Employee.findById(employee.id.value)!!.toEmployeeResponse(permissionGroupCode = null) }

            assertEquals(EmployeeRole.TERMINAL_DEVICE.name, dto.role)
        }

    @Test
    fun `a stale role does not change what the employee may reach`() =
        runBlocking {
            // The role column is NOT the capability authority — `GroupCapabilityResolver` reads
            // `employee_permission_group` by the employee's UUID. So a cashier whose role string went
            // stale still holds exactly a cashier's set: the read fallback lends them nothing, and
            // takes nothing away either.
            val employee = TestData.createCashier(employeeNumber = "LEG030", username = "legacy-basic")
            stampLegacyRole(employee, "BASIC_EMPLOYEE")

            assertEquals(
                CapabilityBundles.forRole(EmployeeRole.CASHIER),
                GroupCapabilityResolver().forEmployee(employee.id.value),
            )
        }

    @Test
    fun `a stale role on an ungrouped row carries NO capabilities`() =
        runBlocking {
            // What a restored pre-V85 archive actually leaves: `RestoreRepairs.attachEmployeesToGroups`
            // joins `permission_group ON code = employees.role`, and a legacy spelling matches no
            // code, so the row gets no group at all. It must reach nothing — never the
            // TERMINAL_DEVICE bundle the read maps the string to.
            val employee = TestData.createCashier(employeeNumber = "LEG040", username = "legacy-basic")
            stampLegacyRole(employee, "BASIC_EMPLOYEE")
            removeGroup(employee.id.value)
            val resolver = GroupCapabilityResolver()

            assertEquals(emptySet(), resolver.forEmployee(employee.id.value))
            assertNull(resolver.groupCodeFor(employee.id.value))
        }

    @Test
    fun `a stale role is not matched by a query for TERMINAL_DEVICE`() =
        runBlocking {
            // `fromDb` is the READ direction only. `SchemaManager.adoptOrCreateTerminalDeviceEmployee`
            // picks the shared login-less station account with `role eq TERMINAL_DEVICE` and then
            // OVERWRITES that row's username and employee number. A pre-V42 `BASIC_EMPLOYEE` row is a
            // person, not a device, so it must stay outside that query — and it does, because the
            // comparison binds the stored string through `toDb` and never runs `fromDb`.
            stampLegacyRole(TestData.createCashier(employeeNumber = "LEG050", username = "legacy-basic"), "BASIC_EMPLOYEE")
            stampLegacyRole(TestData.createCashier(employeeNumber = "LEG051", username = "legacy-terminal"), "TERMINAL")

            val matched =
                dbQuery {
                    Employee
                        .find { EmployeeTable.role eq EmployeeRole.TERMINAL_DEVICE }
                        .map { it.employeeNumber }
                }

            assertFalse("LEG050" in matched, "a pre-V42 person must not become the device principal")
            assertFalse("LEG051" in matched, "a pre-V79 row must not become the device principal")
        }
}
