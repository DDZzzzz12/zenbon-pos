package org.example.project.database

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.backup.data.service.RestoreRepairs
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.testutils.PostgresTestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the restore's permission-group repair on real PostgreSQL, in CI.
 *
 * Models the shape a **pre-V85** archive produces: `employees` fully populated while
 * `permission_group`, `permission_group_capability` and `employee_permission_group` are all empty,
 * because the archive has no CSV for them and the restore TRUNCATEs every table. Without the repair
 * that database commits successfully and then refuses every capability-gated route for every user
 * including the owner — `/api/v1/backup/list` among them, so the operator cannot reach the restore
 * screen to try again. Synthetic data only — no PII — so unlike `realdataTest` this runs in CI.
 *
 * One archive shape gets its own coverage: a **pre-V79** one, whose employees still carry the role
 * string `TERMINAL`. V85's back-fill joins `permission_group ON code = role` and then
 * `RAISE EXCEPTION`s on any employee left ungrouped, so such a row is precisely the case a merely
 * re-runnable V85 would have aborted on rather than repaired.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Restore permission-group repair — real PostgreSQL (zonky)")
class RestorePermissionGroupRepairTest {
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
     * The minimal slice the repair touches, in the shape a pre-V85 archive restores into.
     *
     * The DDL mirrors V85 and `EmployeeTable` rather than being convenient, in one respect that
     * matters: `employees.employee_id`, `created_at` and `updated_at`, and `permission_group`'s two
     * timestamps, are NOT NULL with **no** column default — Exposed supplies a `clientDefault`, not a
     * DB one. A fixture that added `DEFAULT NOW()` here would let an INSERT that omits them pass
     * under test and fail on exactly the installs the repair exists for.
     *
     * `employees` is a `UUIDTable("employees", "employee_id")`, so the primary key is a UUID named
     * `employee_id` — not a `BIGSERIAL id` — and `employee_permission_group.employee_id` references
     * it, so both sides must be UUID or the foreign key will not create.
     */
    private fun seedPreV85Shape(conn: Connection) {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute(
                "DROP TABLE IF EXISTS employee_permission_group, permission_group_capability, " +
                    "permission_group, employees, cash_book CASCADE",
            )
            st.execute(
                """
                CREATE TABLE employees (
                    employee_id     UUID PRIMARY KEY,
                    username        VARCHAR(50) NOT NULL UNIQUE,
                    password_hash   VARCHAR(255) NOT NULL,
                    employee_number VARCHAR(20) NOT NULL UNIQUE,
                    role            VARCHAR(50) NOT NULL,
                    created_at      TIMESTAMP NOT NULL,
                    updated_at      TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE permission_group (
                    id           SERIAL PRIMARY KEY,
                    code         VARCHAR(50)  NOT NULL UNIQUE,
                    name         VARCHAR(100) NOT NULL,
                    description  VARCHAR(500),
                    is_system    BOOLEAN      NOT NULL,
                    is_deletable BOOLEAN      NOT NULL,
                    created_at   TIMESTAMP    NOT NULL,
                    updated_at   TIMESTAMP    NOT NULL
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE permission_group_capability (
                    group_id   INT         NOT NULL REFERENCES permission_group (id) ON DELETE CASCADE,
                    capability VARCHAR(64) NOT NULL,
                    PRIMARY KEY (group_id, capability)
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE employee_permission_group (
                    employee_id UUID NOT NULL PRIMARY KEY REFERENCES employees (employee_id) ON DELETE CASCADE,
                    group_id    INT  NOT NULL REFERENCES permission_group (id) ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            // `apply()` runs every repair, and neither of the other two has a table here:
            // `location_types`, `cash_book`, `shop_info` and `kassenbuch_header` are all simply
            // absent, so both decline on their missing-table guard and this fixture exercises the
            // permission repair alone.
            //
            // That absence is deliberate and load-bearing. `cash_book` used to be stubbed here with
            // one row, purely because `repairCashBooks` had no guard and `apply()` would otherwise
            // throw. Dropping the stub turns the workaround into a gate: remove either guard and
            // every test in this class fails with `relation "…" does not exist`.
        }
    }

    private fun insertEmployee(
        conn: Connection,
        username: String,
        role: String,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO employees " +
                    "(employee_id, username, password_hash, employee_number, role, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), ?, '', ?, ?, NOW(), NOW())",
            ).use { ps ->
                ps.setString(1, username)
                ps.setString(2, username.uppercase())
                ps.setString(3, role)
                ps.executeUpdate()
            }
    }

    @Test
    @DisplayName("a pre-V85 archive gets its groups seeded and every employee attached")
    fun repairSeedsGroupsAndAttachesEveryEmployee() {
        pg.dataSource.connection.use { conn ->
            seedPreV85Shape(conn)
            insertEmployee(conn, "owner", EmployeeRole.ADMIN.name)
            insertEmployee(conn, "till", EmployeeRole.CASHIER.name)
            // The pre-V79 spelling: no EmployeeRole entry and no seeded group code matches it.
            insertEmployee(conn, "legacy-terminal", "TERMINAL")

            RestoreRepairs.apply(conn)

            assertEquals(
                CapabilityBundles.definitions().size,
                scalar(conn, "SELECT count(*) FROM permission_group"),
                "every definition must be seeded",
            )
            assertTrue(
                scalar(conn, "SELECT count(*) FROM permission_group_capability") > 0,
                "the seeded groups must carry capabilities, not just exist",
            )
            // The defect itself, asserted directly: an employee in no group resolves to NO
            // capabilities, which is the 403-for-everyone lockout.
            assertEquals(
                0,
                scalar(
                    conn,
                    "SELECT count(*) FROM employees e WHERE NOT EXISTS (" +
                        "SELECT 1 FROM employee_permission_group g WHERE g.employee_id = e.employee_id)",
                ),
                "no employee may be left ungrouped",
            )
            assertEquals(EmployeeRole.ADMIN.name, groupCodeOf(conn, "owner"), "the owner must reach their own group")
            assertEquals(EmployeeRole.CASHIER.name, groupCodeOf(conn, "till"))

            // The legacy row was normalised AND then adopted as the device principal — so it is
            // found under the canonical username, not its original one, and no second device
            // account was created beside it.
            assertEquals(
                EmployeeRole.TERMINAL_DEVICE.name,
                groupCodeOf(conn, TerminalEmployee.USERNAME),
                "the legacy TERMINAL role must be normalised into TERMINAL_DEVICE, not orphaned",
            )
            assertEquals(3, scalar(conn, "SELECT count(*) FROM employees"), "no duplicate device account")
            assertEquals(
                1,
                scalar(conn, "SELECT count(*) FROM employees WHERE role = '${EmployeeRole.TERMINAL_DEVICE.name}'"),
                "exactly one terminal-device employee may exist",
            )
            assertEquals(
                TerminalEmployee.EMPLOYEE_NUMBER,
                stringOf(conn, "SELECT employee_number FROM employees WHERE username = '${TerminalEmployee.USERNAME}'"),
                "the adopted row must carry the canonical employee number",
            )
        }
    }

    @Test
    @DisplayName("with no terminal-role employee to adopt, a fresh un-loggable device account is seeded")
    fun repairCreatesTheDevicePrincipalWhenNoneCanBeAdopted() {
        pg.dataSource.connection.use { conn ->
            seedPreV85Shape(conn)
            insertEmployee(conn, "owner", EmployeeRole.ADMIN.name)

            RestoreRepairs.apply(conn)

            assertEquals(
                EmployeeRole.TERMINAL_DEVICE.name,
                groupCodeOf(conn, TerminalEmployee.USERNAME),
                "a pre-V79 archive carries no device principal; SchemaManager resolves it by role, " +
                    "so the repair must provision one",
            )
            // Deliberately the empty string: no verifier accepts it, so the account cannot be
            // logged into. It exists only to give a login-less station an employee id.
            assertEquals(
                "",
                stringOf(conn, "SELECT password_hash FROM employees WHERE username = '${TerminalEmployee.USERNAME}'"),
                "the device account must never be a usable credential",
            )
        }
    }

    @Test
    @DisplayName("a populated permission_group is left strictly alone")
    fun repairSkipsPopulatedPermissionGroup() {
        pg.dataSource.connection.use { conn ->
            seedPreV85Shape(conn)
            insertEmployee(conn, "owner", EmployeeRole.ADMIN.name)
            // One operator-created group, as a post-V85 archive would carry. The repair must not
            // "top up" a partially-populated table.
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO permission_group " +
                        "(code, name, description, is_system, is_deletable, created_at, updated_at) " +
                        "VALUES ('WEEKEND_STAFF', 'Weekend staff', NULL, FALSE, TRUE, NOW(), NOW())",
                )
            }

            RestoreRepairs.apply(conn)

            assertEquals(1, scalar(conn, "SELECT count(*) FROM permission_group"), "no group may be seeded")
            assertEquals(
                0,
                scalar(conn, "SELECT count(*) FROM permission_group_capability"),
                "no capability may be added to an operator's own group",
            )
            assertEquals(
                0,
                scalar(conn, "SELECT count(*) FROM employee_permission_group"),
                "no employee may be attached",
            )
            // Strict no-op: the guard covers the whole repair, not only the seeding step.
            assertEquals(1, scalar(conn, "SELECT count(*) FROM employees"), "no device principal may be provisioned")
        }
    }

    @Test
    @DisplayName("the repair is idempotent — a second run seeds no second set")
    fun repairIsIdempotent() {
        pg.dataSource.connection.use { conn ->
            seedPreV85Shape(conn)
            insertEmployee(conn, "owner", EmployeeRole.ADMIN.name)
            insertEmployee(conn, "legacy-terminal", "TERMINAL")

            RestoreRepairs.apply(conn)
            val groups = scalar(conn, "SELECT count(*) FROM permission_group")
            val capabilities = scalar(conn, "SELECT count(*) FROM permission_group_capability")

            RestoreRepairs.apply(conn)
            RestoreRepairs.apply(conn)

            assertEquals(groups, scalar(conn, "SELECT count(*) FROM permission_group"), "no group may be duplicated")
            assertEquals(
                capabilities,
                scalar(conn, "SELECT count(*) FROM permission_group_capability"),
                "no capability may be duplicated",
            )
            assertEquals(2, scalar(conn, "SELECT count(*) FROM employees"), "no second device account")
            assertEquals(
                2,
                scalar(conn, "SELECT count(*) FROM employee_permission_group"),
                "each employee holds exactly one group",
            )
        }
    }

    @Test
    @DisplayName("seeded capabilities are CapabilityBundles' own, not a local copy that can drift")
    fun seededCapabilitiesMatchCapabilityBundles() {
        pg.dataSource.connection.use { conn ->
            seedPreV85Shape(conn)

            RestoreRepairs.apply(conn)

            // Asserted against the real object rather than a literal list: a test carrying its own
            // copy of the capability names would drift exactly the way a hardcoded seed would, and
            // would keep passing while the repair handed a restored shop the wrong reach.
            for (definition in CapabilityBundles.definitions()) {
                assertEquals(
                    definition.capabilities.map { it.name }.toSet(),
                    capabilitiesOf(conn, definition.code),
                    "seeded group ${definition.code} diverged from CapabilityBundles",
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun groupCodeOf(
        conn: Connection,
        username: String,
    ): String? =
        conn
            .prepareStatement(
                "SELECT g.code FROM employees e " +
                    "JOIN employee_permission_group m ON m.employee_id = e.employee_id " +
                    "JOIN permission_group g ON g.id = m.group_id WHERE e.username = ?",
            ).use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }

    private fun capabilitiesOf(
        conn: Connection,
        code: String,
    ): Set<String> =
        conn
            .prepareStatement(
                "SELECT c.capability FROM permission_group_capability c " +
                    "JOIN permission_group g ON g.id = c.group_id WHERE g.code = ?",
            ).use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }

    private fun scalar(
        conn: Connection,
        sql: String,
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun stringOf(
        conn: Connection,
        sql: String,
    ): String? =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }
}
