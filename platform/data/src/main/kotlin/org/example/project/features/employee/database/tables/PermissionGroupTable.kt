package org.example.project.features.employee.database.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A named set of capabilities.
 *
 * Groups are **data** — an operator creates them in Stage 5 — while the `Capability` enum they
 * compose stays in code. That boundary is the core of the design: a capability no route references
 * would be a dead checkbox in the admin UI, and a rule referencing a deleted one would be a silent
 * outage, since the route registry denies by default.
 *
 * [isSystem] marks the seeded groups whose capability set the Stage 5 API refuses to edit — the
 * four role groups plus Stage 3's `ACCOUNTANT`, `AUDITOR` and `ORDER_ONLY`.
 */
object PermissionGroupTable : IntIdTable("permission_group", "id") {
    /** Stable identifier. Four of the seeded codes are exactly the `EmployeeRole` names. */
    val code = varchar("code", 50).uniqueIndex("permission_group_code_key")

    val name = varchar("name", 100)

    val description = varchar("description", 500).nullable()

    val isSystem = bool("is_system")

    val isDeletable = bool("is_deletable")

    val createdAt = datetime("created_at")

    val updatedAt = datetime("updated_at")
}
