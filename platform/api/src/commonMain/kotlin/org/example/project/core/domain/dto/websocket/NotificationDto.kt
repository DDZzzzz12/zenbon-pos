package org.example.project.core.domain.dto.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation

/**
 * Container object for all WebSocket-related DTOs.
 *
 * Organizes DTOs by functionality: real-time events, status updates,
 * and notifications to maintain clear separation of concerns and
 * consistent WebSocket message structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object NotificationDto {
    /**
     * Base WebSocket message interface.
     *
     * All WebSocket messages implement this interface to ensure
     * consistent message structure and type identification.
     */
    @Serializable
    sealed interface NotificationMessage {
        val messageType: String
        val timestamp: String
    }

    /**
     * Table status change notification.
     *
     * Sent when a table's status changes (assigned, freed, reserved, etc.)
     */
    @Serializable
    @SerialName("table_status_update")
    data class TableStatusUpdateDto(
        override val messageType: String = "table_status_update",
        override val timestamp: String,
        val tableId: Long,
        val tableNumber: String,
        val status: String, // TableStatus enum as string
        val sessionId: Long? = null,
        val sessionStartTime: String? = null,
        val employeeId: String? = null,
        val employeeNumber: String? = null,
        val isTraining: Boolean = false,
        /** Open (unpaid) total of the table's current session, 2-dp plain string; null when free or <= 0. */
        val openAmount: String? = null,
    ) : NotificationMessage

    /**
     * Round-robin rotation changed: who is next to receive a table.
     *
     * [isSnapshot] is true when sent as the initial/replayed state rather than a change.
     */
    @Serializable
    @SerialName("rotation_next_update")
    data class RotationNextUpdateDto(
        override val messageType: String = "rotation_next_update",
        override val timestamp: String,
        val version: Long,
        val next: RotationDto.EmployeeRefDto?,
        val isSnapshot: Boolean = false,
    ) : NotificationMessage

    /**
     * Error message for WebSocket communication.
     *
     * Sent when an error occurs in WebSocket processing.
     */
    @Serializable
    data class WebSocketErrorDto(
        override val messageType: String = "error",
        override val timestamp: String,
        val errorCode: String,
        val message: String,
        val details: String? = null,
    ) : NotificationMessage

    /**
     * Connection acknowledgment message.
     *
     * Sent when a WebSocket connection is successfully established.
     */
    @Serializable
    data class ConnectionAckDto(
        override val messageType: String = "connection_ack",
        override val timestamp: String,
        val connectionId: String,
        val message: String = "WebSocket connection established successfully",
    ) : NotificationMessage

    /**
     * Authorization notification message.
     *
     * Sent for hybrid authorization system events:
     * - Approval requests to managers
     * - Approval responses to requesting devices
     * - Session status updates (created, expired, extended)
     */
    @Serializable
    @SerialName("authorization_notification")
    data class AuthorizationNotificationDto(
        override val messageType: String = "authorization_notification",
        override val timestamp: String,
        val notificationType: String, // APPROVAL_REQUEST, APPROVAL_RESPONSE, SESSION_CREATED, SESSION_ENDED, SESSION_EXPIRING
        val data: String, // JSON-encoded auth DTO (ApprovalRequestDto, ApprovalResponseDto, etc.)
        val targetDeviceId: String? = null, // Optional device targeting
    ) : NotificationMessage

    /**
     * TSE connection status update notification.
     *
     * Broadcast to all connected clients when TSE connection state changes
     * (connected/disconnected/mode change). Used to show TSE status in
     * the notification panel and trigger warning dialogs when TSE is disabled.
     */
    @Serializable
    @SerialName("tse_status_update")
    data class TseStatusUpdateDto(
        override val messageType: String = "tse_status_update",
        override val timestamp: String,
        val connected: Boolean,
        val healthy: Boolean,
        val health: TseHealthState = TseHealthState.HEALTHY,
        val mode: String, // "NORMAL", "DISABLED", "SIMULATION"
        val maintenanceOps: Set<String> = emptySet(),
        val message: String? = null,
        val certificateExpirationDate: Long = 0L,
        val daysUntilCertExpiry: Long? = null,
        /**
         * A bundled TSE firmware update is waiting to be applied.
         *
         * Carried on the status broadcast so it reaches an operator who never opens the TSE
         * settings tab. [AGD] 2.8 puts the duty to inform on the manufacturer, and warns that
         * an ERS-User who does not install updates in time runs into legal problems.
         */
        val firmwareUpdateAvailable: Boolean = false,
    ) : NotificationMessage

    /**
     * Data change notification for entity synchronization.
     *
     * Broadcast when a synced entity type is created, updated, or deleted
     * on the server. Clients use this to trigger a targeted re-sync of
     * only the affected entity type instead of a full sync.
     *
     * @property entityType The type of entity that was modified
     * @property operation The mutation operation that occurred
     * @property entityId The ID of the affected entity, or null for batch operations
     */
    @Serializable
    @SerialName("data_change")
    data class DataChangeNotificationDto(
        override val messageType: String = "data_change",
        override val timestamp: String,
        val entityType: ChangeEntityType,
        val operation: DataChangeOperation,
        val entityId: String? = null,
        val seq: Long = 0L, // entry_log.id of this change; 0 = legacy/unknown (hint only)
    ) : NotificationMessage

    /**
     * Billing request notification.
     *
     * Sent to a manager/cashier when a server requests the bill for a table.
     *
     * @property requestedByStation True when the request came from a login-less order station
     *   rather than a person, so the client can say "Order station" instead of naming the shared
     *   device account's employee number. Decided on the server, where the requesting employee's
     *   **username** is in hand — the only sound test for that account (`TerminalEmployee`
     *   documents why its role and employee number are not). Defaults to false so an older client
     *   and every existing construction site keep working.
     */
    @Serializable
    @SerialName("billing_request")
    data class BillingRequestNotificationDto(
        override val messageType: String = "billing_request",
        override val timestamp: String,
        val tableId: Long,
        val tableNumber: String,
        val requestingEmployeeNumber: String,
        val requestedByStation: Boolean = false,
    ) : NotificationMessage

    /**
     * Terminal session terminated notification.
     *
     * Pushed to a device when its terminal binding is revoked, the terminal is deactivated,
     * or its role changes. The client must perform a cold reset and re-pair.
     */
    @Serializable
    @SerialName("terminal_session_terminated")
    data class TerminalSessionTerminatedDto(
        override val messageType: String = "terminal_session_terminated",
        override val timestamp: String,
        val terminalId: String,
        val reason: String, // REVOKED, DEACTIVATED, ROLE_CHANGED
        val requiresRepairing: Boolean = true,
    ) : NotificationMessage

    /**
     * Sent to a connection right after it opens, so a long-lived client (the browser till) can
     * compare [serverVersion] with its own bundle and offer a reload after a server upgrade.
     */
    @Serializable
    @SerialName("server_hello")
    data class ServerHelloDto(
        override val messageType: String = "server_hello",
        override val timestamp: String,
        val serverVersion: String,
    ) : NotificationMessage
}
