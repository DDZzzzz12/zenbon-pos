package org.example.project.utils.log

import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.domain.models.EntryLog
import org.example.project.features.employee.domain.models.Employee
import org.jetbrains.exposed.v1.dao.*
import java.util.UUID

/**
 * Helper utility for logging entity operations to EntryLog table.
 *
 * Provides centralized logging functionality for CREATE, UPDATE, DELETE operations
 * to support deletion synchronization across clients and maintain audit trails.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Helper object for logging entity operations to EntryLog table.
 *
 * Provides centralized logging for CREATE, UPDATE, DELETE operations
 * to support deletion synchronization across clients. All repository
 * operations should use this helper to ensure consistent logging.
 *
 * Usage example:
 * ```kotlin
 * // In route handler, wrap service calls with user context:
 * authenticate("auth-jwt") {
 *     post("/api/products") {
 *         val userId = call.principal<JWTPrincipal>()
 *             ?.payload?.getClaim("employeeNumber")?.asLong() ?: 0L
 *
 *         withCurrentUser(userId) {
 *             // All EntryLogHelper calls will automatically use this userId
 *             service.createProduct(request)
 *         }
 *     }
 * }
 *
 * // In repository (userId is automatically extracted):
 * EntryLogHelper.logOperation(
 *     type = ChangeEntityType.PRODUCT,
 *     operation = LogOperation.CREATE,
 *     entityId = product.id.value,
 *     entityName = product.name
 * )
 * ```
 */
object EntryLogHelper {
    /**
     * Log an entity operation.
     *
     * Creates a new entry in the EntryLog table to track the operation.
     * Automatically extracts the current user ID from coroutine context if available.
     * This enables deletion synchronization across multiple clients and
     * provides a complete audit trail of all entity modifications.
     *
     * @param type Type of entity (PRODUCT, CATEGORY, RTABLE, etc.)
     * @param operation Operation performed (CREATE, UPDATE, DELETE)
     * @param entityId ID of the affected entity
     * @param entityName Name or description of the entity (max 50 chars)
     * @param message Detailed description of what was done (max 500 chars, optional)
     * @param userId Optional user ID override (defaults to current user from context, or 0 for system)
     */
    fun logOperation(
        type: ChangeEntityType,
        operation: LogOperation,
        entityId: String,
        entityName: String,
        message: String? = null,
        userId: String? = null,
    ) {
        try {
            val employee = if (userId != null) Employee.Companion.findById(UUID.fromString(userId)) else null

            EntryLog.Companion.new {
                this.type = type
                this.operation = operation
                this.entityId = entityId
                this.entityName = entityName.take(50) // Truncate to column limit
                this.message = message?.take(500) // Truncate to column limit
                this.userId = employee
            }
        } catch (e: Exception) {
            throw e
        }
    }
}
