package org.example.project.features.employee.database.tables

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.timetracking.database.tables.CostCenterTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

object EmployeeTable : UUIDTable("employees", "employee_id") {
    // Authentication & Identity
    val username = varchar("username", 50).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val employeeNumber = varchar("employee_number", 20).uniqueIndex()

    val employmentStatus = enumerationByName("employment_status", 50, EmploymentStatus::class).default(EmploymentStatus.ACTIVE)

    /**
     * Back-reference to the personnel record. The relationship is stored on both sides —
     * [EmployeeInfoTable.employeeId] is the authoritative link that lookups use, this column is the
     * traversal path `Employee.employeeInfo` (DSFinV-K `BEDIENER_NAME`, `EmployeeMapper`).
     *
     * `SET NULL` rather than the Exposed default `RESTRICT`: deleting a personnel record must not be
     * blocked by the back-reference. Before V90 it was, and every delete failed with an
     * FK violation surfacing as a bare 500 — see V90 and `EmployeeInfoRepositoryImpl.detachBackReference`.
     */
    val infoId = reference("info_id", EmployeeInfoTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // POS-Specific Data

    /**
     * Role — read through [EmployeeRole.getRole], NOT `enumerationByName`.
     *
     * `enumerationByName` maps the column with `EnumerationNameColumnType`, whose `valueFromDB`
     * throws on any string that is not a current enum name. Two spellings this project has retired
     * are still reachable in a live database: `TERMINAL` (renamed by V79) and `BASIC_EMPLOYEE`
     * (renamed to `TERMINAL` by V42). In-place upgrades were rewritten by those migrations, but a
     * **restore reloads the archive's rows after migrations have run**, so neither rewrite can
     * reach a pre-V42/pre-V79 backup — the same hole that let `PRINTERLOCATION` 500 the whole
     * Änderungen screen (see `EntryLogTable.type`).
     *
     * Here the blast radius is larger, because nothing filters this column out of a query: one
     * stale row fails **login** (`EmployeeMapper.toResponse` reads `role`), the entire staff list
     * (`getAllEmployees` / `getEmployeeSummaries`), the employee-performance statistics, and the
     * `personal` custom export. A recovered database would be unreachable for everybody.
     *
     * ## Why this cannot grant access
     *
     * Unlike `entry_log.type`, this column is current state near authorization — so the fallback
     * must never resolve to more capability than the row had. It does not, on three independent
     * counts:
     *
     * 1. **[EmployeeRole.getRole] answers `TERMINAL_DEVICE` for anything it does not recognise** —
     *    the least-privileged constant, never `ADMIN`. `roleSatisfies` treats it as the floor, and
     *    every `isTerminal()` call site *withholds* something (table-session ownership, the
     *    rotation pool, cross-employee billing, clocking in).
     * 2. **The role is not the capability authority.** `RouteAuthorizationPlugin` resolves what a
     *    principal holds from `GroupCapabilityResolver.forEmployee(employeeId)` — the
     *    `employee_permission_group` join, keyed by the employee's UUID. An employee in no group
     *    resolves to the **empty set**, and because that is non-null the `?: forGroupCode(role.name)`
     *    fallback beside it never fires for a human. A restored row whose role string matches no
     *    `permission_group.code` therefore reaches nothing, and `RestoreRepairs` logs it at ERROR.
     * 3. **`fromDb` is the read direction only.** `toDb` still writes `name`, and every `eq`/`neq`
     *    comparison binds the stored string — so `Employee.find { role eq TERMINAL_DEVICE }`, which
     *    is how `SchemaManager` picks the shared device principal to adopt, still does **not** match
     *    a `BASIC_EMPLOYEE` row. Reading it as `TERMINAL_DEVICE` cannot make a person adoptable as
     *    the login-less station account.
     *
     * No retired enum entry is added (the `ChangeEntityType` shape) and no value is rewritten in
     * place — see `RestoreRepairs.normalizeLegacyTerminalRole` for why rewriting `BASIC_EMPLOYEE`
     * would be actively harmful.
     *
     * `sql` must be spelled out because `CustomEnumerationColumnType.sqlType()` has no fallback.
     * `VARCHAR(50)` is exactly what `enumerationByName(length = 50)` emitted on both H2 and
     * Postgres, so the generated DDL is unchanged and no migration is needed.
     */
    val role =
        customEnumeration<EmployeeRole>(
            name = "role",
            sql = "VARCHAR(50)",
            fromDb = { stored: Any -> EmployeeRole.getRole(stored.toString()) },
            toDb = { value: EmployeeRole -> value.name },
        )
    val pin = varchar("pin", 10).nullable()

    // Audit & Tracking
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
    val lastLogin = datetime("last_login").nullable()
    val notes = text("notes").nullable()

    // Optional Advanced Features
    val languagePreference = varchar("language_preference", 10).default("DE")

    /** Whether the employee is in training mode (DSFinV-K AVTraining) */
    val trainingMode = bool("training_mode").default(false)

    // Time tracking
    val defaultCostCenterId = reference("default_cost_center_id", CostCenterTable).nullable()
    val personnelNumber = varchar("personnel_number", 20).nullable()

    /** Whether this employee is in the round-robin rotation pool */
    val inRotationPool = bool("in_rotation_pool").default(false)
}
