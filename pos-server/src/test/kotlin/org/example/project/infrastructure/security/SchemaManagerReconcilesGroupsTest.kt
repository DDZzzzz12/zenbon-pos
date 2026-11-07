package org.example.project.infrastructure.security

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.database.SchemaManager
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SchemaManager`'s group seeding must converge on the definitions, not merely add to them.
 *
 * The seeding was `insertIgnore`-only, so it applied every capability the migrations ADD and none of
 * the ones they REMOVE. V111 deletes `REPORT_READ` and `DOCUMENT_READ` from `TERMINAL_DEVICE` to
 * close defect D4; against a database that already held those rows — a developer's persisted
 * `.dev-data` H2 — the seeding left them in place, so the environment silently kept the exposure the
 * release had closed.
 *
 * `SeededGroupsMatchBundlesTest` could not catch this. `TestDatabase` builds a fresh
 * `h2:mem:test_<nanoTime>` for every run, so "rows already exist and disagree" never occurred there;
 * a test that only ever sees a clean database cannot tell insert-only from reconciling. This class
 * creates the disagreement on purpose and then re-seeds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SchemaManager reconciles permission groups")
class SchemaManagerReconcilesGroupsTest {
    @BeforeAll
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterAll
    fun tearDown() {
        TestDatabase.teardown()
    }

    private fun groupId(code: String) =
        transaction {
            PermissionGroupTable
                .selectAll()
                .first { it[PermissionGroupTable.code] == code }[PermissionGroupTable.id]
        }

    private fun capabilitiesOf(code: String): Set<String> =
        transaction {
            val id = groupId(code)
            PermissionGroupCapabilityTable
                .selectAll()
                .filter { it[PermissionGroupCapabilityTable.group] == id }
                .map { it[PermissionGroupCapabilityTable.capability] }
                .toSet()
        }

    /**
     * Re-runs the real bootstrap, which is what a server start does. Deliberately the whole thing
     * rather than a hand-extracted seeding helper: the defect was in how bootstrap behaves against a
     * database that already has rows, so testing anything smaller would test a different function.
     */
    private fun reseed() = SchemaManager.bootstrapInitialData()

    @Test
    fun `a capability the definitions no longer confer is removed on the next seed`() {
        // Exactly the D4 shape: put REPORT_READ back into TERMINAL_DEVICE the way a pre-Stage-2b
        // database would still have it.
        val id = groupId(EmployeeRole.TERMINAL_DEVICE.name)
        transaction {
            PermissionGroupCapabilityTable.insertIgnore {
                it[group] = id
                it[capability] = Capability.REPORT_READ.name
            }
        }
        assertTrue(Capability.REPORT_READ.name in capabilitiesOf(EmployeeRole.TERMINAL_DEVICE.name))

        reseed()

        assertFalse(
            Capability.REPORT_READ.name in capabilitiesOf(EmployeeRole.TERMINAL_DEVICE.name),
            "seeding must REMOVE a capability the definitions dropped, not just add missing ones",
        )
    }

    @Test
    fun `a capability missing from the database is restored on the next seed`() {
        val id = groupId(EmployeeRole.TERMINAL_DEVICE.name)
        transaction {
            PermissionGroupCapabilityTable.deleteWhere {
                (PermissionGroupCapabilityTable.group eq id) and
                    (PermissionGroupCapabilityTable.capability eq Capability.ORDER_WRITE.name)
            }
        }
        assertFalse(Capability.ORDER_WRITE.name in capabilitiesOf(EmployeeRole.TERMINAL_DEVICE.name))

        reseed()

        assertTrue(Capability.ORDER_WRITE.name in capabilitiesOf(EmployeeRole.TERMINAL_DEVICE.name))
    }

    @Test
    fun `a renamed group does not keep its old label`() {
        val id = groupId("ACCOUNTANT")
        transaction {
            PermissionGroupTable.update({ PermissionGroupTable.id eq id }) {
                it[name] = "Stale Name"
                it[description] = "stale description"
            }
        }

        reseed()

        val definition = CapabilityBundles.definitions().first { it.code == "ACCOUNTANT" }
        val row =
            transaction {
                PermissionGroupTable
                    .selectAll()
                    .first { it[PermissionGroupTable.id] == id }
                    .let { it[PermissionGroupTable.name] to it[PermissionGroupTable.description] }
            }
        assertEquals(definition.name to definition.description, row)
    }

    @Test
    fun `every group converges on its definition after a mutated re-seed`() {
        reseed()
        CapabilityBundles.definitions().forEach { definition ->
            assertEquals(
                definition.capabilities.map { it.name }.toSet(),
                capabilitiesOf(definition.code),
                "${definition.code} did not converge on its definition",
            )
        }
    }

    @Test
    fun `seeding is idempotent`() {
        reseed()
        val before = CapabilityBundles.definitions().associate { it.code to capabilitiesOf(it.code) }
        reseed()
        val after = CapabilityBundles.definitions().associate { it.code to capabilitiesOf(it.code) }
        assertEquals(before, after)
    }
}
