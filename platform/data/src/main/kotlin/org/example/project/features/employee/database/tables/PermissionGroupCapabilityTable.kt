package org.example.project.features.employee.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * The capabilities a group confers.
 *
 * [capability] is a plain string with **no foreign key**, deliberately: the Kotlin `Capability` enum
 * is the authority, and a table of enum values would be a second, drift-prone source of truth. An
 * unrecognised string is dropped at resolve time and logged as an error — it means the database is
 * ahead of the running build.
 */
object PermissionGroupCapabilityTable : Table("permission_group_capability") {
    val group = reference("group_id", PermissionGroupTable, onDelete = ReferenceOption.CASCADE)

    /** A `Capability` enum name. Deliberately unconstrained — see the class KDoc. */
    val capability = varchar("capability", 64)

    override val primaryKey = PrimaryKey(group, capability, name = "permission_group_capability_pkey")
}
