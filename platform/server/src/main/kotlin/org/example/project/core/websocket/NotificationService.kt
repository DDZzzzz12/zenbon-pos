/**
 * Real-time notification service for SSE communication.
 *
 * Provides methods to send real-time notifications for table status changes,
 * order updates, and other POS system events via the SSE notification stream.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Service for sending real-time notifications via the SSE stream.
 *
 * Provides high-level methods to broadcast notifications for specific
 * business events in the POS system, handling message formatting and routing
 * through [NotificationHub]. The SSE stream is a low-latency hint; the database
 * remains the source of truth and the client reconciles on every reconnect.
 */
@OptIn(ExperimentalTime::class)
object NotificationService {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * Send table status update notification to all connected clients.
     *
     * @param tableStatusUpdateDto Table status update DTO
     */
    suspend fun notifyTableStatusUpdate(tableStatusUpdateDto: NotificationDto.TableStatusUpdateDto) {
        try {
            logger.info("Broadcasting table status update: Table ${tableStatusUpdateDto.tableId} -> ${tableStatusUpdateDto.status}")
            NotificationHub.broadcastToAll(tableStatusUpdateDto)
        } catch (e: Exception) {
            logger.error("Failed to send table status update notification", e)
        }
    }

    /**
     * Broadcast the current round-robin "next employee" to all connected clients.
     *
     * @param update Rotation next update DTO (versioned by [org.example.project.features.order.data.service.RotationNextNotifier])
     */
    suspend fun notifyRotationNext(update: NotificationDto.RotationNextUpdateDto) {
        try {
            logger.info("Broadcasting rotation next update: version ${update.version} -> ${update.next?.employeeNumber}")
            NotificationHub.broadcastToAll(update)
        } catch (e: Exception) {
            logger.error("Failed to send rotation next update notification", e)
        }
    }

    /**
     * Send data change notification.
     *
     * Broadcasts to ALL connected clients (including the originator) so each can
     * selectively re-sync the affected entity type. The originator is included on
     * purpose: its API write only updated its in-memory admin state, not the local
     * SQLDelight cache the POS/order screen reads — so it must apply this change too.
     * (The cursor pull at /api/changes remains authoritative for cursor advancement.)
     *
     * Note: [seq] is currently always 0 — populating it with the real entry_log.id
     * is deferred because notifications fire before the entry_log row is written.
     * The SSE `data_change` message is a low-latency hint only; the `/api/changes`
     * pull feed is authoritative for cursor advancement.
     *
     * @param entityType The type of entity that was modified
     * @param operation The mutation operation (CREATED, UPDATED, DELETED)
     * @param entityId The ID of the affected entity, or null for batch operations
     * @param seq Entry-log sequence number hint (default 0 = unknown/deferred)
     * @param excludeEmployeeId Retained for source compatibility; intentionally NOT
     *   applied to data-change hints (the originator must receive its own change to
     *   refresh its local cache). Data-change notifications always reach all clients.
     */
    suspend fun notifyDataChange(
        entityType: ChangeEntityType,
        operation: DataChangeOperation,
        entityId: String? = null,
        seq: Long = 0L,
        excludeEmployeeId: String? = null,
    ) {
        try {
            val notification =
                NotificationDto.DataChangeNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    entityType = entityType,
                    operation = operation,
                    entityId = entityId,
                    seq = seq,
                )

            // Broadcast to ALL clients, INCLUDING the originator. The originating client must also
            // receive the hint so it applies the change to its own local SQLDelight cache: the API
            // response only updated its in-memory admin state, not the offline cache that the POS /
            // order screen reads. Excluding the originator left its cache stale until the next full
            // sync (e.g. choice groups never appeared in the configurator on the editing device).
            // `excludeEmployeeId` is intentionally NOT applied to data-change hints.
            logger.info("Broadcasting data change notification: $entityType $operation ${entityId?.let { "id=$it" } ?: "(batch)"}")
            NotificationHub.broadcastToAll(notification, excludeEmployeeId = null)
        } catch (e: Exception) {
            logger.error("Failed to send data change notification for $entityType $operation", e)
        }
    }

    /**
     * Convenience overload for Long entity IDs.
     *
     * @param entityType The type of entity that was modified
     * @param operation The mutation operation (CREATED, UPDATED, DELETED)
     * @param entityId The Long ID of the affected entity
     * @param seq Entry-log sequence number hint (default 0 = unknown/deferred)
     * @param excludeEmployeeId Retained for source compatibility; intentionally NOT
     *   applied to data-change hints (the originator must receive its own change to
     *   refresh its local cache). Data-change notifications always reach all clients.
     */
    suspend fun notifyDataChange(
        entityType: ChangeEntityType,
        operation: DataChangeOperation,
        entityId: Long,
        seq: Long = 0L,
        excludeEmployeeId: String? = null,
    ) {
        notifyDataChange(entityType, operation, entityId.toString(), seq, excludeEmployeeId)
    }

    /**
     * Send billing request notification to the table owner.
     *
     * @param billingRequest The billing request notification DTO
     * @param targetEmployeeId The employee ID of the table owner
     */
    suspend fun notifyBillingRequest(
        billingRequest: NotificationDto.BillingRequestNotificationDto,
        targetEmployeeId: String,
    ) {
        try {
            logger.info(
                "Sending billing request: Table ${billingRequest.tableId} from employee ${billingRequest.requestingEmployeeNumber} to $targetEmployeeId",
            )
            NotificationHub.sendToEmployee(targetEmployeeId, billingRequest)
            // Park for reconcile-on-connect recovery (client may be offline beyond the SSE replay buffer).
            PendingBillingRequestStore.add(targetEmployeeId, billingRequest)
        } catch (e: Exception) {
            logger.error("Failed to send billing request notification", e)
        }
    }

    /**
     * Send error notification to a specific employee.
     *
     * @param employeeId Target employee ID
     * @param errorCode Error code for categorization
     * @param message Error message
     * @param details Optional detailed error information
     */
    suspend fun notifyError(
        employeeId: String,
        errorCode: String,
        message: String,
        details: String? = null,
    ) {
        try {
            val errorMessage =
                NotificationDto.WebSocketErrorDto(
                    timestamp = Clock.System.now().toString(),
                    errorCode = errorCode,
                    message = message,
                    details = details,
                )

            logger.warn("Sending error notification to employee $employeeId: $errorCode - $message")
            NotificationHub.sendToEmployee(employeeId, errorMessage)
        } catch (e: Exception) {
            logger.error("Failed to send error notification", e)
        }
    }

    /**
     * Send system-wide notification to all connected users.
     *
     * @param message Notification message
     * @param excludeEmployeeId Optional employee ID to exclude from broadcast
     */
    suspend fun broadcastSystemNotification(
        message: String,
        excludeEmployeeId: String? = null,
    ) {
        try {
            val notification =
                NotificationDto.ConnectionAckDto(
                    timestamp = Clock.System.now().toString(),
                    connectionId = "system",
                    message = message,
                )

            logger.info("Broadcasting system notification: $message")
            NotificationHub.broadcastToAll(notification, excludeEmployeeId)
        } catch (e: Exception) {
            logger.error("Failed to send system notification", e)
        }
    }

    /**
     * Get current notification connection statistics.
     *
     * @return Map containing connection statistics
     */
    fun getConnectionStatistics(): Map<String, Any> = NotificationHub.getConnectionStats()
}
