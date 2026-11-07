package org.example.project.infrastructure.security

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seeded groups must reproduce [CapabilityBundles] exactly.
 *
 * Both seed paths are generated from the same source but neither is checked by the compiler: the
 * Flyway V110 rows were produced by a script and pasted in as static SQL, and `SchemaManager`'s H2
 * seeding is ordinary Kotlin that could drift from it. This is the standing guard for both — add a
 * capability to a bundle without regenerating the migration and this fails, rather than the
 * difference surfacing as a mysterious 403 in production.
 *
 * Runs against H2, so it is the `SchemaManager` path under test here; `SchemaFlywayParityTest`
 * covers the DDL half against real PostgreSQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Seeded permission groups reproduce CapabilityBundles")
class SeededGroupsMatchBundlesTest {
    @BeforeAll
    fun setUp() {
        TestDatabase.setup()
    }

    @AfterAll
    fun tearDown() {
        TestDatabase.teardown()
    }

    private fun seeded(code: String): Set<Capability> =
        transaction {
            val groupId =
                PermissionGroupTable
                    .selectAll()
                    .firstOrNull { it[PermissionGroupTable.code] == code }
                    ?.get(PermissionGroupTable.id)
                    ?: error("no permission_group row with code '$code' — seeding did not run")

            PermissionGroupCapabilityTable
                .selectAll()
                .filter { it[PermissionGroupCapabilityTable.group] == groupId }
                .mapNotNull { row ->
                    val name = row[PermissionGroupCapabilityTable.capability]
                    Capability.entries.firstOrNull { it.name == name }
                }.toSet()
        }

    @Test
    fun `each seeded group matches its bundle exactly`() {
        // Iterates DEFINITIONS, not EmployeeRole.entries: since Stage 3 the seeded groups include
        // ACCOUNTANT, AUDITOR and ORDER_ONLY, which have no role. Iterating roles would have left
        // the three new groups unchecked while still passing.
        for (definition in CapabilityBundles.definitions()) {
            assertEquals(
                definition.capabilities,
                seeded(definition.code),
                "seeded group ${definition.code} diverged from CapabilityBundles — regenerate the " +
                    "migration seed and re-check SchemaManager.initializeDefaults",
            )
        }
    }

    @Test
    fun `every seeded group carries the display metadata its definition declares`() {
        // The name and description are seeded from the same object, so a group renamed in Kotlin
        // without a migration would otherwise show the old name forever on an upgraded database.
        for (definition in CapabilityBundles.definitions()) {
            val row =
                transaction {
                    PermissionGroupTable
                        .selectAll()
                        .firstOrNull { it[PermissionGroupTable.code] == definition.code }
                        ?.let { it[PermissionGroupTable.name] to it[PermissionGroupTable.description] }
                } ?: error("no permission_group row with code '${definition.code}'")

            assertEquals(definition.name to definition.description, row, definition.code)
        }
    }

    @Test
    fun `the ADMIN group holds every capability`() {
        // Enumerated in the migration rather than derived in SQL: a clever INSERT ... SELECT over the
        // other groups' rows would omit any capability no other group holds — exactly the RESERVED
        // set — and quietly lock an admin out of TSE administration.
        assertEquals(Capability.entries.toSet(), seeded(EmployeeRole.ADMIN.name))
    }

    /**
     * The nesting invariant holds for the four ROLE groups only.
     *
     * It is deliberately not universal: `AUDITOR` holds `AUDIT_READ`, which no cashier holds, while
     * holding none of the order-taking capabilities every cashier holds. Purpose-built groups are
     * shapes, not tiers — that is what makes them worth having, and asserting a total order over
     * them would force each one to be a superset of the till.
     */
    @Test
    fun `the groups nest strictly, terminal through manager`() {
        val terminal = seeded(EmployeeRole.TERMINAL_DEVICE.name)
        val cashier = seeded(EmployeeRole.CASHIER.name)
        val manager = seeded(EmployeeRole.MANAGER.name)

        assertTrue(cashier.containsAll(terminal), "CASHIER must contain TERMINAL_DEVICE: ${terminal - cashier}")
        assertTrue(manager.containsAll(cashier), "MANAGER must contain CASHIER: ${cashier - manager}")
    }

    @Test
    fun `no seeded capability string is unrecognised`() {
        // The column deliberately has no FK to the enum, so nothing but this stops a typo in the
        // generated SQL from becoming a silently-ignored row.
        val known = Capability.entries.map { it.name }.toSet()
        val unknown =
            transaction {
                PermissionGroupCapabilityTable
                    .selectAll()
                    .map { it[PermissionGroupCapabilityTable.capability] }
                    .filterNot { it in known }
                    .toSet()
            }
        assertEquals(emptySet(), unknown, "seeded capability strings matching no enum value")
    }
}
