package org.example.project.features.table.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import java.util.UUID

/**
 * Repository interface for table session data access operations.
 *
 * Defines contract for managing table session entities including CRUD operations,
 * session lifecycle management, and queries for operational reporting.
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
interface TableSessionRepository {
    fun getAllSessions(
        limit: Int = 50,
        offset: Int = 0,
        tableId: Long? = null,
        employeeId: UUID? = null,
        completed: Boolean? = null,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
    ): List<TableSession>

    fun getSessionById(id: Long): TableSession?

    fun getActiveSessionByTableId(tableId: Long): TableSession?

    fun getSessionsByTableId(
        tableId: Long,
        limit: Int = 50,
        offset: Int = 0,
    ): List<TableSession>

    fun getSessionsByEmployeeId(
        employeeId: UUID,
        limit: Int = 50,
        offset: Int = 0,
    ): List<TableSession>

    /**
     * Get or create a session for a table.
     *
     * @return Pair of (wasCreated, session)
     */
    fun getOrCreateSession(
        table: RTable,
        employee: Employee,
    ): Pair<Boolean, TableSession>

    /**
     * Complete a table session.
     *
     * @throws NoSuchElementException if session not found
     * @throws IllegalStateException if session already completed or has open items
     */
    fun completeSession(id: Long): TableSession

    /**
     * The one closing rule: ends [id]'s session and frees its table when no order item is OPEN.
     * Paid and cancelled lines do not keep a session open. Returns the completed session, or null
     * when an OPEN item remains or the session was already completed. Needs an ambient
     * transaction, and the caller must already hold the table row lock (TABLE before SESSION).
     */
    fun completeIfNoOpenItems(id: Long): TableSession?

    fun assignCustomerAndAddress(
        sessionId: Long,
        customerId: UUID,
        deliveryAddressId: UUID?,
        isDelivery: Boolean,
        deliveryPhoneNumber: String? = null,
    ): TableSession?

    fun removeCustomerAndAddress(sessionId: Long): TableSession?
}
