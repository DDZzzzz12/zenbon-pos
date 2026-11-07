package org.example.project.features.employee.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Which permission group an employee belongs to.
 *
 * The primary key is [employee] **alone** — that is what enforces exactly one group per employee,
 * the composition rule the parent spec fixed (§4.2). A composite key would silently permit the
 * multi-group union that rule rejects, and "why can this person do X?" would stop having a single
 * answer — which matters when a Betriebsprüfer asks.
 *
 * Per-employee exceptions return in Stage 5 as a separate additive grant table, designed rather
 * than inherited from the retired `EXCEPTION_*` mechanism.
 */
object EmployeePermissionGroupTable : Table("employee_permission_group") {
    val employee = reference("employee_id", EmployeeTable, onDelete = ReferenceOption.CASCADE)

    val group = reference("group_id", PermissionGroupTable)

    override val primaryKey = PrimaryKey(employee)
}
