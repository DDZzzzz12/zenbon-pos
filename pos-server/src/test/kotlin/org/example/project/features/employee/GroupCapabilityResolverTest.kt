package org.example.project.features.employee

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("GroupCapabilityResolver")
class GroupCapabilityResolverTest {
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

    private fun reassign(
        employeeId: UUID,
        role: EmployeeRole,
    ) = transaction {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .first { it[PermissionGroupTable.code] == role.name }[PermissionGroupTable.id]
        EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
        EmployeePermissionGroupTable.insert {
            it[EmployeePermissionGroupTable.employee] = employeeId
            it[EmployeePermissionGroupTable.group] = groupId
        }
    }

    private fun removeGroup(employeeId: UUID) =
        transaction {
            EmployeePermissionGroupTable.deleteWhere { EmployeePermissionGroupTable.employee eq employeeId }
        }

    @Test
    fun `resolves an employee to their group's capabilities`() =
        runBlocking {
            val id = TestData.createManager(employeeNumber = "RES001", username = "res-mgr").id.value
            assertEquals(CapabilityBundles.forRole(EmployeeRole.MANAGER), resolver.forEmployee(id))
        }

    @Test
    fun `an employee with no group resolves to the empty set, never a default`() =
        runBlocking {
            // Fails CLOSED on purpose. Falling back to a role-derived default would hand a
            // mis-migrated employee somebody else's access, which is worse than a visible 403.
            val id = TestData.createCashier(employeeNumber = "RES002", username = "res-nogroup").id.value
            removeGroup(id)
            resolver.invalidate(id)
            assertEquals(emptySet(), resolver.forEmployee(id))
        }

    @Test
    fun `invalidate makes the next resolve see a changed group`() =
        runBlocking {
            val id = TestData.createCashier(employeeNumber = "RES003", username = "res-invalidate").id.value
            assertEquals(CapabilityBundles.forRole(EmployeeRole.CASHIER), resolver.forEmployee(id))

            reassign(id, EmployeeRole.MANAGER)
            resolver.invalidate(id)

            assertEquals(CapabilityBundles.forRole(EmployeeRole.MANAGER), resolver.forEmployee(id))
        }

    @Test
    fun `without invalidate the cache keeps the old set`() =
        runBlocking {
            // Pins the contract that makes invalidation MANDATORY rather than a nicety. This is
            // exactly the bug TerminalCapabilityResolver shipped once: a revoked station kept
            // resolving its stale capability until process restart.
            val id = TestData.createCashier(employeeNumber = "RES004", username = "res-stale").id.value
            resolver.forEmployee(id)

            reassign(id, EmployeeRole.MANAGER)

            assertEquals(CapabilityBundles.forRole(EmployeeRole.CASHIER), resolver.forEmployee(id))
        }

    @Test
    fun `invalidateAll drops every entry`() =
        runBlocking {
            val id = TestData.createCashier(employeeNumber = "RES005", username = "res-all").id.value
            resolver.forEmployee(id)

            reassign(id, EmployeeRole.MANAGER)
            resolver.invalidateAll()

            assertEquals(CapabilityBundles.forRole(EmployeeRole.MANAGER), resolver.forEmployee(id))
        }
}
