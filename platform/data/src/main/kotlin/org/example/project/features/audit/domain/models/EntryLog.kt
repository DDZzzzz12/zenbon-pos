package org.example.project.features.audit.domain.models

import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.employee.domain.models.Employee
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for EntryLog.
 *
 * Provides object-relational mapping for operation logging with proper
 * type-safe database operations. Tracks CREATE, UPDATE, DELETE operations
 * across all major entities for audit trails and deletion synchronization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * EntryLog entity for tracking all entity operations.
 *
 * Logs CREATE, UPDATE, and DELETE operations for auditing and
 * cross-client deletion synchronization. Each log entry records
 * what operation was performed, on which entity, by whom, and when.
 */
class EntryLog(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<EntryLog>(EntryLogTable)

    /** Type of entity that was affected (PRODUCT, CATEGORY, etc.) */
    var type by EntryLogTable.type

    /** Operation that was performed (CREATE, UPDATE, DELETE) */
    var operation by EntryLogTable.operation

    /** ID of the affected entity */
    var entityId by EntryLogTable.entityId

    /** Name or description of the affected entity (for readability) */
    var entityName by EntryLogTable.entityName

    /** Detailed description of what was done */
    var message by EntryLogTable.message

    /** ID of the user who performed the operation (0 for system) */
    var userId by Employee.Companion optionalReferencedOn EntryLogTable.userId

    /** Timestamp when the operation was performed (epoch milliseconds) */
    var createdAt by EntryLogTable.createdAt
}
