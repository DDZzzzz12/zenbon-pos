/**
 * Restaurant table entity class for table management.
 *
 * Manages restaurant tables including seating capacity, status tracking,
 * and session management for customer service operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.domain.models

import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Entity representing a restaurant table for customer seating.
 *
 * Manages table information including capacity, type, status, and current session.
 * Provides methods for starting and ending customer service sessions.
 */
class RTable(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<RTable>(RTableTable)

    /** Table number or identifier */
    var tableNumber by RTableTable.tableNumber

    /** Number of seats available at this table */
    var seats by RTableTable.seats

    /** Location type (e.g., DINING_AREA, BAR, PATIO) */
    var locationTypeRef by LocationType referencedOn RTableTable.locationTypeId

    /** Current operational status of the table */
    var tableStatus by RTableTable.tableStatus

    var updateAt by RTableTable.updatedAt

    /** Currently active session (optional) */
    var currentSession by TableSession optionalReferencedOn RTableTable.currentSessionId

    // Floor plan position and layout properties
    /** X coordinate position on floor plan canvas */
    var positionX by RTableTable.positionX

    /** Y coordinate position on floor plan canvas */
    var positionY by RTableTable.positionY

    /** Rotation angle in degrees (0-360) */
    var rotation by RTableTable.rotation

    /** Width of table representation on floor plan */
    var width by RTableTable.width

    /** Height of table representation on floor plan */
    var height by RTableTable.height

    /** Whether this table is active (soft delete support) */
    var isActive by RTableTable.isActive

    /** Assignment priority (lower = assigned first in auto-assign, 0 = default) */
    var assignmentPriority by RTableTable.assignmentPriority

    /** System-provisioned table (the virtual VOUCHER table): cannot be edited or deleted by an operator. */
    var isSystem by RTableTable.isSystem

    val isVirtual: Boolean
        get() = locationTypeRef.isVirtual

    val isTakeaway: Boolean
        get() = locationTypeRef.isTakeaway

    /**
     * Mark table as free and end current session if still active.
     *
     * Resets the table to a clean state, ready for new customers.
     */
    fun reset() {
        currentSession = null
        tableStatus = TableStatus.FREE
    }

    /**
     * Start a new customer service session for this table.
     *
     * Creates a new session record and marks the table as occupied.
     *
     * @param employee The employee starting the session
     * @return The newly created TableSession
     */
    fun startSession(employee: Employee): TableSession {
        val session =
            TableSession.new {
                table = this@RTable
                employeeId = employee
                startTime = currentTimeStamp()
                completed = false
                isTraining = employee.trainingMode
            }
        currentSession = session
        tableStatus = TableStatus.OCCUPIED
        return session
    }
}
