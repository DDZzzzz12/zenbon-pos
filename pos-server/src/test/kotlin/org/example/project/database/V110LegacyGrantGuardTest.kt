package org.example.project.database

import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The legacy-grant guard: what it drops, and what it refuses to.
 *
 * The guard shipped as V110; the v1.1.0 squash folded it into the permission-group seed and the
 * rename pass renumbered that file. The class name keeps the historical V110 for continuity, but
 * no version or filename is written down below — the migration is found by what it does, see
 * [readGuardMigration].
 *
 * The guard originally aborted on ANY row in `employee_permissions`, asserting the table was "empty
 * in every real deployment". It is not: until the grant mechanism was retired,
 * `InitialSetupServiceImpl.executeOwnerSetup` created the owner with
 * `permissions = PermissionEnum.entries.map { it.code }.toSet()`, so **every** install provisioned
 * before that commit carries nine rows — the owner's. The guard therefore aborted on every
 * existing database, and no later migration could repair it: the RAISE aborts the transaction and
 * halts the chain, so nothing above it would ever run.
 *
 * The guard now distinguishes by whether dropping could REVOKE anything:
 *  - an ADMIN's grants are subsumed (the ADMIN group is `Capability.entries`), so they are dropped;
 *  - anyone else's may confer access their role does not, so those still abort — by name, so the
 *    operator can act.
 *
 * The guard SQL is **extracted from the migration file itself** rather than restated here. A copy
 * would keep passing after the real one drifted, which is exactly the failure this guards against.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Legacy-grant guard — real PostgreSQL (zonky)")
class V110LegacyGrantGuardTest {
    private lateinit var pg: PostgresTestDatabase

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) pg.close()
    }

    /**
     * The `DO $$ … END $$;` block that inspects `employee_permissions`.
     *
     * Located by content, not by line number, so ordinary edits to the migration do not silently
     * point this at a different block.
     */
    private fun guardSql(): String {
        val migration = readGuardMigration()

        val blocks = migration.split("DO $$").drop(1).map { "DO $$" + it }
        val guard =
            blocks.firstOrNull { it.contains("employee_permissions") && it.contains("subsumed_rows") }
                ?: error("No DO-block inspecting employee_permissions found in the guard migration — has it been renamed?")
        val end = guard.indexOf("END $$;")
        assertTrue(end > 0, "guard block has no END terminator")
        return guard.substring(0, end + "END $$;".length)
    }

    /**
     * The SQL of whichever migration currently carries the legacy-grant guard, located by CONTENT.
     *
     * Neither half of a Flyway filename is stable: the description changes with a squash and the
     * version changes with a rename pass. This test was pinned to the literal `V110__` prefix and
     * broke the moment the permission-group seed was renumbered to V85 — the eighth time a pinned
     * migration identifier rotted on this branch. It is therefore resolved the same way the other
     * seven were fixed: by what the script *does*. The guard is the only place in the whole chain
     * that inspects `employee_permissions` for `subsumed_rows`, so that pair identifies it
     * uniquely, and [guardSql] would fail anyway if it did not.
     *
     * Exactly one script must match. Zero means the guard was deleted or rewritten; more than one
     * means it was duplicated — both are real defects, and both are reported by name.
     */
    private fun readGuardMigration(): String {
        val dir =
            checkNotNull(javaClass.classLoader.getResource("db/migration")) {
                "db/migration not on the test classpath"
            }
        val scripts =
            checkNotNull(File(dir.toURI()).listFiles { f: File -> f.name.endsWith(".sql") }) {
                "db/migration is not a readable directory"
            }
        val carriers =
            scripts
                .sortedBy { it.name }
                .map { it to it.readText() }
                .filter { (_, sql) -> sql.contains("employee_permissions") && sql.contains("subsumed_rows") }

        assertEquals(
            1,
            carriers.size,
            "exactly one migration must carry the legacy-grant guard (a DO-block over " +
                "employee_permissions/subsumed_rows); found ${carriers.map { it.first.name }}",
        )
        return carriers.single().second
    }

    /** The pre-guard shape: `employees` from the chain, the two legacy tables from Exposed. */
    private fun seedSchema(c: Connection) {
        c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS employee_permissions")
            s.execute("DROP TABLE IF EXISTS employees")
            s.execute(
                "CREATE TABLE employees (employee_id uuid PRIMARY KEY, username varchar(64) NOT NULL, role varchar(32) NOT NULL)",
            )
            s.execute("CREATE TABLE employee_permissions (employee_id uuid NOT NULL, permission_id int NOT NULL)")
        }
    }

    private fun grant(
        c: Connection,
        username: String,
        role: String,
        grants: Int,
    ) {
        c.createStatement().use { s ->
            s.execute(
                "INSERT INTO employees (employee_id, username, role) VALUES (gen_random_uuid(), '$username', '$role')",
            )
            s.execute(
                "INSERT INTO employee_permissions (employee_id, permission_id) " +
                    "SELECT e.employee_id, g FROM employees e, generate_series(1, $grants) g WHERE e.username = '$username'",
            )
        }
    }

    @Test
    fun `the owner's nine grants pass, because ADMIN already holds every capability`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            // Exactly what executeOwnerSetup produced on every pre-retirement install.
            grant(c, "owner", "ADMIN", grants = 9)

            c.createStatement().use { it.execute(guardSql()) }

            val remaining =
                c.createStatement().executeQuery("SELECT COUNT(*) FROM employee_permissions").use {
                    it.next()
                    it.getInt(1)
                }
            assertEquals(9, remaining, "the guard must not delete rows itself — the migration's DROP TABLE does that")
        }
    }

    @Test
    fun `a non-admin's grant still aborts, and names the holder`() {
        pg.dataSource.connection.use { c ->
            seedSchema(c)
            grant(c, "owner", "ADMIN", grants = 9)
            grant(c, "till_pete", "CASHIER", grants = 2)

            val failure = assertFailsWith<SQLException> { c.createStatement().use { it.execute(guardSql()) } }

            val message = failure.message.orEmpty()
            assertContains(message, "till_pete", message = "the abort must name who is blocking it")
            assertContains(message, "CASHIER", message = "and the role, so the operator can judge the risk")
            assertTrue(
                "owner" !in message,
                "the admin's subsumed grants are not the problem and must not be reported as blocking: $message",
            )
        }
    }

    @Test
    fun `a database built purely from the chain has no legacy table and passes`() {
        pg.dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS employee_permissions")
                s.execute("DROP TABLE IF EXISTS employees")
                s.execute("CREATE TABLE employees (employee_id uuid PRIMARY KEY, username varchar(64) NOT NULL, role varchar(32) NOT NULL)")
            }
            // to_regclass path: nothing to check, and no exception.
            c.createStatement().use { it.execute(guardSql()) }
        }
    }
}
