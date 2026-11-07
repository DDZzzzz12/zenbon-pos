package org.example.project.features.backup.data.service

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.terminal.domain.TerminalEmployee
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Repairs values a backup could not carry, applied after the data loads and before the restore
 * commits.
 *
 * A backup taken before a column existed cannot supply it. `BackupRestoreServiceImpl.syntheticFill`
 * gives such a column a type-appropriate zero, which is fine for a scalar and wrong for a foreign
 * key: `bigint` yields `0`, and no row has id 0. These repairs reconstruct the real value.
 *
 * **Every repair must be idempotent.** A newer backup WILL carry the column, and the repair must
 * then do nothing rather than duplicating rows.
 */
object RestoreRepairs {
    private val logger = LoggerFactory.getLogger(RestoreRepairs::class.java)

    /** Applies every repair. Call inside the restore transaction, before the integrity check. */
    fun apply(conn: Connection) {
        repairLocationTypes(conn)
        repairSystemFlags(conn)
        repairCashBooks(conn)
        repairPermissionGroups(conn)
    }

    /**
     * Re-seeds the five location types for an archive that does not carry them.
     *
     * `location_types` is reference data: V54 created and seeded it, and a fresh install gets the
     * same rows from `SchemaManager`. No backup taken before V54 has a CSV for it, and the
     * restore's TRUNCATE covers every table — so after the load it is empty while every
     * `r_table.location_type_id` and `room_element.location_type_id` holds a real id (mapped from
     * the legacy `location_type` string by `BackupRestoreServiceImpl.restoreFillOverrides`).
     * Without this the FK check aborts a restore that is otherwise entirely sound.
     *
     * The timestamps are set explicitly rather than left to a column default: V54 declares them
     * with `DEFAULT CURRENT_TIMESTAMP`, but the Exposed table a fresh install builds does not, so
     * omitting them fails the NOT NULL on exactly the installs this repair exists for.
     *
     * The ids are V54's fixed ids, not sequence-assigned, because the mapping that produced the
     * referencing values names them literally. `is_takeaway` rides along on purpose: it decides a
     * VAT treatment, so restoring the row without it would be worse than not restoring it.
     *
     * **Fires only when the table is empty after the load**, which is exactly the archive-lacks-it
     * case; an archive that carries location types keeps its own, including any the operator added.
     * Idempotent by construction: after a successful repair the table is no longer empty.
     */
    private fun repairLocationTypes(conn: Connection) {
        // A repair whose table is absent is a no-op, not an error: this runs against whatever
        // schema the restore landed on, and a caller exercising one repair in isolation should not
        // have to build the tables of every other.
        if (!tableExists(conn, "location_types")) {
            logger.debug("RestoreRepairs: no location_types table — nothing to repair")
            return
        }
        val existing = countRows(conn, "location_types")
        if (existing > 0) {
            logger.info(
                "RestoreRepairs: location_types carries {} row(s) — archive has them, no repair applied",
                existing,
            )
            return
        }

        val inserted =
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO location_types
                        (id, name, english_name, chinese_name, is_virtual, is_takeaway, sort_order,
                         created_at, updated_at)
                    VALUES
                        (1, 'Innenbereich',   'Indoor',     '室内',    false, false, 1, NOW(), NOW()),
                        (2, 'Außenbereich',   'Outdoor',    '户外',    false, false, 2, NOW(), NOW()),
                        (3, 'Abholung',       'Takeaway',   '外卖',    false, true,  3, NOW(), NOW()),
                        (4, 'Gutschein',      'Voucher',    '代金券',  true,  false, 4, NOW(), NOW()),
                        (5, 'Schnellverkauf', 'Quick Sale', '快速销售', true,  false, 5, NOW(), NOW())
                    """.trimIndent(),
                )
            }
        // The sequence is left past the seeded ids so a later insert cannot collide with them.
        conn.createStatement().use { st ->
            st.execute("SELECT setval('location_types_id_seq', GREATEST(5, (SELECT MAX(id) FROM location_types)))")
        }
        logger.warn("RestoreRepairs: archive predates location_types — re-seeded {} row(s)", inserted)
    }

    /**
     * Re-asserts `is_system` on the rows the system provisions: the virtual Gutschein and
     * Schnellverkauf location types and the VOUCHER table (V131).
     *
     * An archive taken before V131 has no `is_system` column, so `syntheticFill` gives every row
     * `false` and the guards that refuse to edit or delete a system record would silently stop
     * protecting them. Idempotent: setting an already-true flag changes nothing, and an operator-
     * created row is never touched (matched by the fixed seeded id / the VOUCHER table on the
     * VOUCHER location type).
     */
    private fun repairSystemFlags(conn: Connection) {
        if (tableExists(conn, "location_types") && columnExists(conn, "location_types", "is_system")) {
            conn.createStatement().use { st ->
                st.executeUpdate("UPDATE location_types SET is_system = TRUE WHERE id IN (4, 5) AND is_system = FALSE")
            }
        }
        if (tableExists(conn, "r_table") && columnExists(conn, "r_table", "is_system")) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "UPDATE r_table SET is_system = TRUE WHERE table_number = 'VOUCHER' AND location_type_id = 4 AND is_system = FALSE",
                )
            }
        }
    }

    private fun columnExists(
        conn: Connection,
        table: String,
        column: String,
    ): Boolean =
        conn
            .prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE lower(table_name) = ? AND lower(column_name) = ?",
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

    private fun tableExists(
        conn: Connection,
        table: String,
    ): Boolean =
        conn.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun countRows(
        conn: Connection,
        table: String,
    ): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /**
     * Re-runs V84's backfill for a backup that predates the cash-book dimension.
     *
     * **Fires only when `cash_book` is empty after the load.** That is exactly the pre-V84 shape:
     * the archive has no `cash_book.csv`, so after the restore's TRUNCATE the table stays empty
     * while every `kassenbuch_header.cash_book_id` holds the synthetic `0`. Left alone the restore
     * commits an orphaned Kassenbuch and DSFinV-K fails later with
     * `Cannot find cash_book WHERE id=0`.
     *
     * If `cash_book` holds any row the archive carries the dimension, and this repair does
     * **nothing** — not even creating a missing Ladenkasse. A dangling header in that archive is
     * genuine corruption, not a missing column, and repointing it would silently move entries from
     * a non-fiscal petty-cash book into the fiscal one: a fiscal reclassification (DSFinV-K selects
     * by `cashBook.isFiscal`, and the TSE signing queue `require`s it). Such a header is left for
     * [ForeignKeyIntegrityCheck] to abort the restore on.
     *
     * Idempotent by construction: after a successful repair `cash_book` is no longer empty, so a
     * second run is a no-op.
     */
    private fun repairCashBooks(conn: Connection) {
        // A repair whose tables are absent is a no-op, not an error — the same reasoning as the
        // other two. All three are listed, not just `cash_book`: the INSERT reads `shop_info` and
        // the UPDATE writes `kassenbuch_header`, so guarding only the table this repair is named
        // after would still throw on a caller that has one but not the others.
        val required = listOf("cash_book", "shop_info", "kassenbuch_header")
        val missing = required.filterNot { tableExists(conn, it) }
        if (missing.isNotEmpty()) {
            logger.debug("RestoreRepairs: {} absent — no cash-book repair possible", missing)
            return
        }

        val existingBooks = countCashBooks(conn)
        if (existingBooks > 0) {
            logger.info(
                "RestoreRepairs: cash_book carries {} row(s) — archive has the cash-book " +
                    "dimension, no repair applied",
                existingBooks,
            )
            return
        }

        conn.createStatement().use { st ->
            val booksCreated =
                st.executeUpdate(
                    """
                    INSERT INTO cash_book (shop_id, name, is_fiscal, datev_account, close_interval, created_at)
                    SELECT si.shop_id, 'Ladenkasse', TRUE, '1000', 'MANUAL', NOW()
                    FROM shop_info si
                    WHERE NOT EXISTS (
                        SELECT 1 FROM cash_book cb WHERE cb.shop_id = si.shop_id AND cb.is_fiscal
                    )
                    """.trimIndent(),
                )

            val headersRepointed =
                st.executeUpdate(
                    """
                    UPDATE kassenbuch_header h
                    SET cash_book_id = cb.id
                    FROM cash_book cb
                    WHERE cb.shop_id = h.shop_id
                      AND cb.is_fiscal
                      AND NOT EXISTS (SELECT 1 FROM cash_book x WHERE x.id = h.cash_book_id)
                    """.trimIndent(),
                )

            if (booksCreated > 0 || headersRepointed > 0) {
                // Worth WARN, not DEBUG: a restore that repoints 273 headers is a materially
                // different event from one that repairs nothing, and the operator should be able
                // to tell which happened from the log alone.
                logger.warn(
                    "RestoreRepairs: backup predates the cash-book dimension — " +
                        "created {} fiscal cash book(s), repointed {} kassenbuch header(s)",
                    booksCreated,
                    headersRepointed,
                )
            } else {
                logger.info("RestoreRepairs: cash books need no repair")
            }
        }
    }

    /** Row count of `cash_book` — the guard that decides whether the archive predates V84. */
    private fun countCashBooks(conn: Connection): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM cash_book").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    /**
     * The role string V79 renamed to [EmployeeRole.TERMINAL_DEVICE]. No enum entry matches it, so a
     * row still carrying it joins no group code at all.
     */
    private const val LEGACY_TERMINAL_ROLE = "TERMINAL"

    /**
     * Re-runs V85's permission-group seed and back-fill for an archive that predates the capability
     * model — the repair that keeps a recovered database reachable at all.
     *
     * **Fires only when `permission_group` is empty after the load.** That is exactly the pre-V85
     * shape: the archive has no `permission_group.csv`, so after the restore's TRUNCATE the table
     * stays empty while `employees` is fully populated. Left alone, every capability-gated route
     * answers 403 for **every** user including the owner — `GroupCapabilityResolver` fails closed, so
     * an employee in no group reaches nothing — and `/api/v1/backup/list` is gated too, so the
     * operator cannot even get back to the restore screen to try again. Flyway cannot repair it
     * either: the restored `flyway_schema_history` reads `V90 << Flyway Baseline >>`, so V85 is
     * marked applied and can never run. The archive's legacy `employee_permissions` rows have
     * nowhere to land — V85's tail drops that table.
     *
     * The four steps are **ordered**, and each depends on the one before:
     *  1. normalise the pre-V79 role string. V79 renamed `TERMINAL` to `TERMINAL_DEVICE`; a row still
     *     carrying the old value matches no group code, so it must be fixed **before** the back-fill
     *     or it orphans — and before step 3, or it is not adoptable and the device account is
     *     duplicated instead.
     *  2. seed the groups and their capabilities from [CapabilityBundles].
     *  3. provision the shared device principal, adopting before creating.
     *  4. attach every employee to the group its role names.
     *
     * If `permission_group` holds any row the archive carries the model, and this repair does
     * **nothing at all** — not even provisioning a missing device principal. Topping up a
     * partially-populated table would mean deciding which of an operator's own groups are "missing",
     * and the seeded groups are `is_system`/non-deletable, so a half-seed is not recoverable through
     * the API.
     *
     * Idempotent by construction: after a successful repair `permission_group` is no longer empty.
     */
    private fun repairPermissionGroups(conn: Connection) {
        // A repair whose tables are absent is a no-op, not an error — the same reasoning as
        // repairLocationTypes. This also keeps RestoreForeignKeyIntegrityTest's narrow fixture, which
        // creates none of these tables, running through apply() unchanged.
        val required = listOf("employees", "permission_group", "permission_group_capability", "employee_permission_group")
        val missing = required.filterNot { tableExists(conn, it) }
        if (missing.isNotEmpty()) {
            logger.debug("RestoreRepairs: {} absent — no permission-group repair possible", missing)
            return
        }

        val existingGroups = countRows(conn, "permission_group")
        if (existingGroups > 0) {
            logger.info(
                "RestoreRepairs: permission_group carries {} row(s) — archive has the capability " +
                    "model, no repair applied",
                existingGroups,
            )
            return
        }

        val normalizedRoles = normalizeLegacyTerminalRole(conn)
        val seededGroups = seedPermissionGroups(conn)
        val devicePrincipal = provisionDevicePrincipal(conn)
        val attached = attachEmployeesToGroups(conn)
        warnIfAnyEmployeeIsUngrouped(conn)

        // WARN, not INFO: a restore that re-seeds the entire authorisation model is a materially
        // different event from one that repairs nothing, and the operator should be able to tell
        // which happened from the log alone.
        logger.warn(
            "RestoreRepairs: archive predates the permission-group model — normalised {} legacy " +
                "'{}' role(s), seeded {} group(s) from CapabilityBundles, device principal {}, " +
                "attached {} employee(s) to their role's group",
            normalizedRoles,
            LEGACY_TERMINAL_ROLE,
            seededGroups,
            devicePrincipal,
            attached,
        )
    }

    /**
     * Step 1 — rewrites the pre-V79 role string, mirroring V79's own
     * `UPDATE employees SET role = 'TERMINAL_DEVICE' WHERE role = 'TERMINAL'`.
     *
     * `updated_at` is deliberately left alone, exactly as that migration leaves it: this corrects how
     * a value is spelled, it is not a change to the employee.
     *
     * V42's even older `BASIC_EMPLOYEE` is **deliberately not handled**, and the reason is stronger
     * than "no migration would produce that mapping" — one did:
     * `V42__rename_basic_employee_role_to_terminal.sql` is exactly
     * `UPDATE employees SET role = 'TERMINAL' WHERE role = 'BASIC_EMPLOYEE'`, so composing it with
     * V79 gives `BASIC_EMPLOYEE -> TERMINAL_DEVICE` with no invention at all.
     *
     * Applying it here would still be wrong, because **the meaning of the role changed under the
     * rename**. Pre-V42 `BASIC_EMPLOYEE` was a *person* — rank-and-file staff who clocked in and
     * viewed their own time entries. V79 turned the same lineage into a *device* role: its own
     * comment says "TERMINAL/TERMINAL_DEVICE is a device role (not a person)". So rewriting the
     * string would:
     *
     *  - feed [provisionDevicePrincipal], which runs next and takes
     *    `WHERE role = 'TERMINAL_DEVICE' ORDER BY created_at LIMIT 1` — rank-and-file rows are
     *    typically the *oldest*, so the earliest such person would have their `username` and
     *    `employee_number` overwritten with the shared station account's. Their login is destroyed
     *    and the station is attributed to them.
     *  - hand every other one the `TERMINAL_DEVICE` group via [attachEmployeesToGroups], a
     *    **widening** from the no-capability state they are in today, while also making them
     *    ineligible to own a table session or join the rotation pool.
     *
     * Leaving the string alone costs those accounts a manual group assignment;
     * [warnIfAnyEmployeeIsUngrouped] names them at ERROR. The row still *reads* — `EmployeeTable.role`
     * maps the column with `customEnumeration` so a retired spelling cannot 500 login or the staff
     * list — it simply is not silently reclassified. Read fix, not a rewrite.
     */
    private fun normalizeLegacyTerminalRole(conn: Connection): Int =
        conn.prepareStatement("UPDATE employees SET role = ? WHERE role = ?").use { ps ->
            ps.setString(1, EmployeeRole.TERMINAL_DEVICE.name)
            ps.setString(2, LEGACY_TERMINAL_ROLE)
            ps.executeUpdate()
        }

    /**
     * Step 2 — seeds every group and its capabilities, driven by [CapabilityBundles.definitions].
     *
     * Generated from the bundles rather than written out as literals on purpose: V85 and
     * `SchemaManager` are both generated from that same list, and a third hand-kept copy would be
     * free to drift from both while `SeededGroupsMatchBundlesTest`, which only holds those two
     * together, stayed green.
     *
     * `is_system`/`is_deletable` are `TRUE`/`FALSE` throughout, as V85 writes them — the Stage 5 API
     * must refuse to edit or delete a seeded group.
     *
     * The timestamps are set explicitly rather than left to a column default: V85 declares
     * `created_at`/`updated_at` NOT NULL with **no** default (Exposed supplies a `clientDefault`),
     * so omitting them fails the NOT NULL on exactly the installs this repair exists for. The same
     * trap `repairLocationTypes` records.
     *
     * No `setval` afterwards, unlike `repairLocationTypes`: ids come from `permission_group`'s own
     * SERIAL sequence rather than being named literally, and the restore's
     * `TRUNCATE … RESTART IDENTITY` already reset it.
     */
    private fun seedPermissionGroups(conn: Connection): Int {
        val insertGroup =
            "INSERT INTO permission_group " +
                "(code, name, description, is_system, is_deletable, created_at, updated_at) " +
                "VALUES (?, ?, ?, TRUE, FALSE, NOW(), NOW()) RETURNING id"
        val insertCapability = "INSERT INTO permission_group_capability (group_id, capability) VALUES (?, ?)"

        var seeded = 0
        conn.prepareStatement(insertGroup).use { groupStatement ->
            conn.prepareStatement(insertCapability).use { capabilityStatement ->
                CapabilityBundles.definitions().forEach { definition ->
                    groupStatement.setString(1, definition.code)
                    groupStatement.setString(2, definition.name)
                    groupStatement.setString(3, definition.description)
                    val groupId =
                        groupStatement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    definition.capabilities.forEach { capability ->
                        capabilityStatement.setInt(1, groupId)
                        capabilityStatement.setString(2, capability.name)
                        capabilityStatement.addBatch()
                    }
                    seeded++
                }
                capabilityStatement.executeBatch()
            }
        }
        return seeded
    }

    /**
     * Step 3 — ensures the shared login-less device account exists, mirroring V79 steps 6a/6b and
     * `SchemaManager.adoptOrCreateTerminalDeviceEmployee` in that order.
     *
     * **Adopt before create.** A pre-V79 archive carries a terminal-role employee under some other
     * username, and `SchemaManager` resolves the device principal by *role*; a blind insert would
     * leave two `TERMINAL_DEVICE` rows, which is precisely the duplicate V79's `NOT EXISTS` guard
     * exists to prevent. Step 1 must already have run, or a row still spelled `TERMINAL` is passed
     * over here and then duplicated.
     *
     * The seeded row's password hash is the empty string, **deliberately**: no verifier accepts it,
     * so the account cannot be logged into. It exists only to give a login-less station a real
     * employee id to attribute orders to for fiscal purposes, and must never be a usable credential.
     *
     * Returns a short label for the summary log rather than a count — which of the three arms ran is
     * the interesting fact, and "0 rows" would not distinguish "already present" from "failed".
     */
    private fun provisionDevicePrincipal(conn: Connection): String {
        val adopted =
            conn
                .prepareStatement(
                    """
                    UPDATE employees
                    SET username = ?, employee_number = ?, updated_at = NOW()
                    WHERE employee_id = (
                        SELECT employee_id FROM employees WHERE role = ? ORDER BY created_at LIMIT 1
                    )
                    AND NOT EXISTS (SELECT 1 FROM employees WHERE username = ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, TerminalEmployee.USERNAME)
                    ps.setString(2, TerminalEmployee.EMPLOYEE_NUMBER)
                    // Not `IN ('TERMINAL', 'TERMINAL_DEVICE')` as V79 writes it: step 1 has already
                    // folded the legacy spelling in, so one value is the whole population.
                    ps.setString(3, EmployeeRole.TERMINAL_DEVICE.name)
                    ps.setString(4, TerminalEmployee.USERNAME)
                    ps.executeUpdate()
                }
        if (adopted > 0) return "adopted an existing terminal-role employee"

        val created =
            conn
                .prepareStatement(
                    """
                    INSERT INTO employees
                        (employee_id, username, password_hash, employee_number, role, created_at, updated_at)
                    SELECT gen_random_uuid(), ?, '', ?, ?, NOW(), NOW()
                    WHERE NOT EXISTS (SELECT 1 FROM employees WHERE username = ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, TerminalEmployee.USERNAME)
                    ps.setString(2, TerminalEmployee.EMPLOYEE_NUMBER)
                    ps.setString(3, EmployeeRole.TERMINAL_DEVICE.name)
                    ps.setString(4, TerminalEmployee.USERNAME)
                    ps.executeUpdate()
                }
        return if (created > 0) "seeded a fresh login-less account" else "already present"
    }

    /**
     * Step 4 — V85's own back-fill: each employee joins the group whose code equals their role.
     *
     * `employee_permission_group`'s primary key is `employee_id` **alone**, so an employee holds at
     * most one group; `ON CONFLICT DO NOTHING` makes that explicit rather than relying on
     * `permission_group.code` being unique to keep the join single-valued.
     */
    private fun attachEmployeesToGroups(conn: Connection): Int =
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO employee_permission_group (employee_id, group_id)
                SELECT e.employee_id, g.id
                FROM employees e
                JOIN permission_group g ON g.code = e.role
                ON CONFLICT (employee_id) DO NOTHING
                """.trimIndent(),
            )
        }

    /**
     * Reports — but does **not** abort on — an employee whose role matches no seeded group.
     *
     * The deliberate divergence from V85, which `RAISE EXCEPTION`s here. That guard is right during
     * an upgrade, where failing stops a half-migrated deploy; it is wrong during a *restore*, where
     * aborting throws away the entire recovery over one unrecognisable role string and leaves the
     * operator with nothing. Attaching the other employees and naming this one costs that account a
     * manual group assignment; aborting costs everybody the database.
     *
     * Such a row still fails closed — no group means no capability — so this cannot widen anyone's
     * access. It is logged at ERROR because it needs an administrator.
     */
    private fun warnIfAnyEmployeeIsUngrouped(conn: Connection) {
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    """
                    SELECT COUNT(*), COALESCE(string_agg(DISTINCT e.role, ', '), '')
                    FROM employees e
                    WHERE NOT EXISTS (
                        SELECT 1 FROM employee_permission_group g WHERE g.employee_id = e.employee_id
                    )
                    """.trimIndent(),
                ).use { rs ->
                    rs.next()
                    val ungrouped = rs.getInt(1)
                    if (ungrouped == 0) return
                    logger.error(
                        "RestoreRepairs: {} restored employee(s) carry a role matching no permission " +
                            "group ({}) and will reach nothing until an administrator assigns them one. " +
                            "The restore is NOT aborted — locking every user out of a recovered " +
                            "database is worse than leaving these accounts ungrouped.",
                        ungrouped,
                        rs.getString(2),
                    )
                }
        }
    }
}
