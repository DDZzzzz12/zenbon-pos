package org.example.project.features.table.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.table.domain.repository.TableSessionRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.slf4j.LoggerFactory
import java.util.UUID

class TableSessionRepositoryImpl : TableSessionRepository {
    private val logger = LoggerFactory.getLogger(TableSessionRepositoryImpl::class.java)

    override fun getAllSessions(
        limit: Int,
        offset: Int,
        tableId: Long?,
        employeeId: UUID?,
        completed: Boolean?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
    ): List<TableSession> {
        var query = TableSession.Companion.all()

        // Apply table filter if provided
        tableId?.let {
            query = TableSession.Companion.find { TableSessionTable.table eq it }
        }

        // Apply employee filter if provided
        employeeId?.let { empId ->
            val baseQuery =
                if (tableId != null) {
                    (TableSessionTable.table eq tableId) and (TableSessionTable.employeeId eq empId)
                } else {
                    TableSessionTable.employeeId eq empId
                }
            query = TableSession.Companion.find { baseQuery }
        }

        // Apply completion status filter if provided
        completed?.let { isCompleted ->
            val previousCondition =
                when {
                    tableId != null && employeeId != null ->
                        (TableSessionTable.table eq tableId) and (TableSessionTable.employeeId eq employeeId) and
                            (TableSessionTable.completed eq isCompleted)

                    tableId != null ->
                        (TableSessionTable.table eq tableId) and (TableSessionTable.completed eq isCompleted)

                    employeeId != null ->
                        (TableSessionTable.employeeId eq employeeId) and (TableSessionTable.completed eq isCompleted)

                    else ->
                        TableSessionTable.completed eq isCompleted
                }
            query = TableSession.Companion.find { previousCondition }
        }

        // Apply date filters if provided
        startDate?.let { start ->
            val dateCondition = buildDateCondition(tableId, employeeId, completed, start, endDate)
            query = TableSession.Companion.find { dateCondition }
        }

        val sessions =
            query
                .limit(limit)
                .offset(offset.toLong())
                .orderBy(TableSessionTable.id to SortOrder.DESC)
                .toList()
        logger.debug("Retrieved ${sessions.size} sessions with filters: tableId=$tableId, employeeNumber=$employeeId, completed=$completed")
        return sessions
    }

    private fun buildDateCondition(
        tableId: Long?,
        employeeId: UUID?,
        completed: Boolean?,
        startDate: LocalDateTime,
        endDate: LocalDateTime?,
    ): Op<Boolean> {
        var condition: Op<Boolean> = TableSessionTable.startTime greaterEq startDate

        endDate?.let { end ->
            condition = condition and (TableSessionTable.startTime lessEq end)
        }

        tableId?.let { tId ->
            condition = condition and (TableSessionTable.table eq tId)
        }

        employeeId?.let { eId ->
            condition = condition and (TableSessionTable.employeeId eq eId)
        }

        completed?.let { comp ->
            condition = condition and (TableSessionTable.completed eq comp)
        }

        return condition
    }

    override fun getSessionById(id: Long): TableSession? {
        val session = TableSession.Companion.findById(id)
        if (session != null) {
            logger.debug("Found session with id: $id")
        } else {
            logger.warn("Session not found with id: $id")
        }
        return session
    }

    override fun getActiveSessionByTableId(tableId: Long): TableSession? {
        val session =
            TableSession.Companion
                .find {
                    (TableSessionTable.table eq tableId) and (TableSessionTable.completed eq false)
                }.firstOrNull()

        logger.debug("Active session for table $tableId: ${session?.id?.value}")
        return session
    }

    override fun getSessionsByTableId(
        tableId: Long,
        limit: Int,
        offset: Int,
    ): List<TableSession> {
        val sessions =
            TableSession.Companion
                .find { TableSessionTable.table eq tableId }
                .limit(limit)
                .offset(offset.toLong())
                .orderBy(TableSessionTable.startTime to SortOrder.DESC)
                .toList()

        logger.debug("Found ${sessions.size} sessions for table: $tableId")
        return sessions
    }

    override fun getSessionsByEmployeeId(
        employeeId: UUID,
        limit: Int,
        offset: Int,
    ): List<TableSession> {
        val sessions =
            TableSession.Companion
                .find { TableSessionTable.employeeId eq employeeId }
                .limit(limit)
                .offset(offset.toLong())
                .orderBy(TableSessionTable.startTime to SortOrder.DESC)
                .toList()

        logger.debug("Found ${sessions.size} sessions for employee: $employeeId")
        return sessions
    }

    override fun getOrCreateSession(
        table: RTable,
        employee: Employee,
    ): Pair<Boolean, TableSession> {
        // Check if table already has an active session
        val activeSession =
            TableSession
                .find {
                    (TableSessionTable.table eq table.id) and (TableSessionTable.completed eq false)
                }.firstOrNull()

        if (activeSession != null) {
            // Validate training mode compatibility
            if (activeSession.isTraining != employee.trainingMode) {
                throw IllegalStateException(
                    "Training mode mismatch: employee training=${employee.trainingMode}, " +
                        "session training=${activeSession.isTraining} on table ${table.tableNumber}",
                )
            }
            // The normal path when a table that already has an open session is opened again: reuse, not a fault.
            logger.debug("Reusing active session ${activeSession.id.value} for table ${table.id.value}")
            return Pair(false, activeSession)
        }

        // Spec 2026-09-14 §4.2: the last line behind TableSessionOwnerPolicy. A terminal account (an order
        // station's shared terminal-device employee) never owns a NEW session; the policy answers every
        // legitimate case with a 409 before reaching here, so this only fires for a path that bypassed it.
        check(!employee.role.isTerminal()) {
            "A terminal account cannot own a new session on table ${table.tableNumber}; " +
                "sessions are opened through TableSessionOwnerPolicy"
        }
        val session = table.startSession(employee)

        logger.info("Created new session: ${session.id} for table ${table.tableNumber} by employee ${employee.username}")

        return Pair(true, session)
    }

    override fun completeSession(id: Long): TableSession {
        val session =
            TableSession.Companion.findById(id)
                ?: throw NoSuchElementException("Session not found: $id")

        if (session.completed) {
            logger.warn("Attempt to complete already completed session: $id")
            throw IllegalStateException("Session is already completed")
        }

        if (session.orderItems.any { it.itemStatus == ItemStatus.OPEN }) {
            throw IllegalStateException("Session has open order items")
        }

        session.table.reset()

        // Use the entity's end method
        session.end()
        logger.info("Completed session: $id")

        return session
    }

    override fun completeIfNoOpenItems(id: Long): TableSession? {
        val session = TableSession.findById(id) ?: return null
        if (session.completed) return null
        if (session.orderItems.any { it.itemStatus == ItemStatus.OPEN }) return null
        return completeSession(id)
    }

    override fun assignCustomerAndAddress(
        sessionId: Long,
        customerId: UUID,
        deliveryAddressId: UUID?,
        isDelivery: Boolean,
        deliveryPhoneNumber: String?,
    ): TableSession? {
        logger.debug("Assigning customer $customerId to session $sessionId (delivery: $isDelivery)")

        // Find the session
        val session = TableSession.findById(sessionId) ?: return null

        // Check if session is still active
        if (session.completed) {
            logger.warn("Cannot assign customer to completed session $sessionId")
            throw IllegalStateException("Cannot assign customer to a completed session")
        }

        // Find the customer
        val customer =
            org.example.project.features.customer.domain.models.Customer
                .findById(customerId)
                ?: throw NoSuchElementException("Customer not found: $customerId")

        // If delivery mode, validate and set delivery address
        if (isDelivery) {
            if (deliveryAddressId == null) {
                logger.warn("Delivery address required for delivery session")
                throw IllegalArgumentException("Delivery address is required for delivery sessions")
            }

            // Find and validate delivery address
            val address =
                org.example.project.features.customer.domain.models.CustomerAddress
                    .findById(deliveryAddressId)
                    ?: throw NoSuchElementException("Address not found: $deliveryAddressId")

            // Verify address belongs to customer
            if (address.customer.id.value != customerId) {
                logger.warn("Address $deliveryAddressId does not belong to customer $customerId")
                throw IllegalArgumentException("Delivery address does not belong to the specified customer")
            }

            // Assign delivery information
            session.deliveryAddress = address
        }

        // Assign customer to session
        session.customer = customer
        session.delivery = isDelivery
        session.deliveryPhoneNumber = deliveryPhoneNumber

        logger.info("Successfully assigned customer $customerId to session $sessionId (delivery: $isDelivery)")
        return session
    }

    override fun removeCustomerAndAddress(sessionId: Long): TableSession? {
        logger.debug("Removing customer assignment from session $sessionId")

        // Find the session
        val session = TableSession.findById(sessionId) ?: return null

        // Check if session is still active
        if (session.completed) {
            logger.warn("Cannot modify completed session $sessionId")
            throw IllegalStateException("Cannot modify a completed session")
        }

        // Clear customer and delivery information
        session.customer = null
        session.deliveryAddress = null
        session.delivery = false
        session.deliveryPhoneNumber = null

        logger.info("Successfully removed customer assignment from session $sessionId")
        return session
    }
}
