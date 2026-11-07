package org.example.project.infrastructure.security

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.database.SchemaManager
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A **fresh** install must provision the shared login-less `terminal-device` employee, in its group.
 *
 * `initDB` has two paths and only one of them ran the seed. An upgraded database gets the row from
 * `V79__release_tse_audit_terminal.sql` steps 6a/6b; a fresh one builds the schema with Exposed and
 * then calls `FlywayConfig.baselineAtLatest`, which marks V79 applied **without executing it** — so
 * the row was never created. `TerminalEmployee.requireRow` then answered 500 for every station
 * session exchange, and a paired ORDER_STATION could never come up. Confirmed live on a freshly
 * initialized install: `GET /api/employees/summary` returned `[]`.
 *
 * `TestDatabase.setup()` takes exactly that fresh path — H2, Exposed DDL, no Flyway — so this class
 * is the fresh install under test.
 *
 * The capability assertion is the half that matters most. An employee row that exists but belongs to
 * no permission group resolves to NOTHING, because [GroupCapabilityResolver] fails closed by design,
 * and the resulting 403 surfaces nowhere near its cause. That has been the same defect three times
 * here (see `NoDirectEmployeeCreationTest`), so the row's mere existence is not the property worth
 * asserting — its resolved capability set is.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A fresh install seeds the shared terminal-device employee")
class TerminalEmployeeSeededTest {
    private lateinit var resolver: GroupCapabilityResolver

    @BeforeAll
    fun setUp() {
        TestDatabase.setup()
        resolver = GroupCapabilityResolver()
    }

    @AfterAll
    fun tearDown() {
        TestDatabase.teardown()
    }

    private fun terminalRows(): List<UUID> =
        transaction {
            EmployeeTable
                .selectAll()
                .where { EmployeeTable.username eq TerminalEmployee.USERNAME }
                .map { it[EmployeeTable.id].value }
        }

    @Test
    fun `the row exists after a fresh bootstrap`() {
        val row =
            transaction {
                TerminalEmployee.findRow()?.let {
                    Triple(it.role, it.employeeNumber, it.passwordHash)
                }
            }

        assertNotNull(row, "no '${TerminalEmployee.USERNAME}' employee — a fresh install cannot open a station session")
        assertEquals(EmployeeRole.TERMINAL_DEVICE, row.first)
        assertEquals(TerminalEmployee.EMPLOYEE_NUMBER, row.second)
        // Empty on purpose, exactly as V79 writes it: no verifier accepts it, so the account is not
        // a credential anybody can log in with.
        assertEquals("", row.third)
    }

    @Test
    fun `it resolves a non-empty capability set`() =
        runBlocking {
            val id = transaction { TerminalEmployee.requireRow().id.value }
            resolver.invalidate(id)

            val capabilities = resolver.forEmployee(id)

            assertTrue(
                capabilities.isNotEmpty(),
                "the seeded terminal employee belongs to no permission group, so it can reach " +
                    "nothing at all — the station's orders will 403 for a reason that looks unrelated",
            )
            assertEquals(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), capabilities)
        }

    @Test
    fun `re-seeding leaves exactly one row and keeps the group`() =
        runBlocking {
            val before = terminalRows()
            assertEquals(1, before.size)

            SchemaManager.bootstrapInitialData()

            assertEquals(before, terminalRows(), "seeding must be idempotent — a boot must not add a second row")
            resolver.invalidate(before.single())
            assertEquals(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), resolver.forEmployee(before.single()))
        }

    @Test
    fun `a re-seed re-attaches a group that was lost`() =
        runBlocking {
            val id = terminalRows().single()
            transaction { EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq id } }
            resolver.invalidate(id)
            assertEquals(emptySet(), resolver.forEmployee(id))

            SchemaManager.bootstrapInitialData()

            resolver.invalidate(id)
            assertEquals(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), resolver.forEmployee(id))
        }

    @Test
    fun `an existing terminal-role employee is adopted rather than duplicated`() =
        runBlocking {
            // V79 step 6a. Recreate the shape it guards against: a terminal-role account under some
            // other username, and no canonical row. Inserted through the table rather than the DAO
            // entity, which `NoDirectEmployeeCreationTest` reserves for TestData.
            val legacyId =
                transaction {
                    EmployeeTable.deleteWhere { EmployeeTable.username eq TerminalEmployee.USERNAME }
                    EmployeeTable
                        .insertAndGetId {
                            it[username] = "legacy-terminal"
                            it[passwordHash] = ""
                            it[employeeNumber] = "LEGACY-TER"
                            it[role] = EmployeeRole.TERMINAL_DEVICE
                        }.value
                }

            SchemaManager.bootstrapInitialData()

            assertEquals(
                listOf(legacyId),
                terminalRows(),
                "the existing terminal-role employee must BE the shared account — inserting a second " +
                    "one leaves two TERMINAL_DEVICE rows and the wrong one may answer the lookup",
            )
            assertEquals(TerminalEmployee.EMPLOYEE_NUMBER, transaction { TerminalEmployee.requireRow().employeeNumber })
            resolver.invalidate(legacyId)
            assertEquals(CapabilityBundles.forRole(EmployeeRole.TERMINAL_DEVICE), resolver.forEmployee(legacyId))
        }
}
