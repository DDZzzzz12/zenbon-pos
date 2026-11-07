/**
 * Data Transfer Objects for authentication and authorization.
 *
 * Provides DTOs for temporary manager sessions and approval request workflows
 * in the hybrid authorization system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.auth

import kotlinx.serialization.Serializable

/**
 * Container object for all authentication/authorization-related DTOs.
 *
 * Organizes DTOs for temporary sessions, approval requests, and authorization
 * to maintain clear separation of concerns and consistent API structure.
 */
object AuthDto {
    // ========== Temporary Manager Session ==========

    /**
     * Request to create a temporary manager session on a device.
     *
     * @property managerUsername Username of the manager
     * @property managerPin PIN code for authentication
     */
    @Serializable
    data class TemporarySessionRequestDto(
        val managerUsername: String,
        val managerPin: String,
        val deviceId: String,
        /**
         * The capability the blocked action needed, if the caller knows it.
         *
         * Optional because the generic "manager override" dialog is opened from the drawer and does
         * not know what prompted it; that case lends `ElevationScope.TILL_OVERRIDES` instead. A
         * caller that DOES know — the approval-request path names its operation — gets a session
         * scoped to exactly that one capability.
         *
         * A step-up no longer replaces the cashier's identity with the manager's, so this is the
         * whole of what it grants.
         */
        val requestedCapability: String? = null,
    )

    /**
     * Response containing temporary session information.
     *
     * @property sessionId Unique session identifier
     * @property managerId Manager's employee ID
     * @property managerName Manager's display name
     * @property managerRole Manager's role (MANAGER or ADMIN)
     * @property deviceId Device where session is active
     * @property requestingEmployeeId ID of the employee who requested the session
     * @property expiresAt ISO timestamp when session expires
     * @property remainingSeconds Seconds until expiration
     * @property hardCapAt ISO timestamp the session can never be extended past (the manager's PIN
     *   entry plus the server's fixed maximum lifetime); null from a server that predates the cap.
     *   When [expiresAt] equals it, the session ends for good and the manager's PIN is needed again.
     */
    @Serializable
    data class TemporarySessionResponseDto(
        val sessionId: String,
        val managerId: String,
        val managerName: String,
        val managerRole: String,
        val deviceId: String,
        val requestingEmployeeId: String,
        val expiresAt: String,
        val remainingSeconds: Int,
        val hardCapAt: String? = null,
    )

    // ========== Approval Request System ==========

    /**
     * Request for manager approval of a restricted operation.
     *
     * @property requestId Unique request identifier
     * @property requestingEmployeeId ID of employee requesting approval
     * @property requestingEmployeeName Name of requesting employee
     * @property deviceId Device where request originated
     * @property operation Operation type (e.g., "CANCEL_ORDER_ITEM")
     * @property requiredPermission Permission required for operation
     * @property details Operation-specific details for manager to review
     * @property timestamp ISO timestamp of request
     */
    @Serializable
    data class ApprovalRequestDto(
        val requestId: String,
        val requestingEmployeeId: String,
        val requestingEmployeeNumber: String = "",
        val deviceId: String,
        val terminalName: String = "",
        val operation: String,
        /**
         * Capability names the blocked operation needs.
         *
         * Was `requiredPermissions`, carrying `PermissionEnum` names, until per-employee grants were
         * retired. A manager reading an approval prompt should see the authority actually being
         * asked for.
         */
        val requiredCapabilities: Set<String>,
        val details: Map<String, String>,
        val timestamp: String,
    )

    /**
     * Response to an approval request (approve or deny).
     *
     * @property requestId Request being responded to
     * @property approved Whether request was approved
     * @property approvingManagerId ID of manager who responded
     * @property approvingManagerName Name of manager who responded
     * @property reason Optional reason for denial
     */
    @Serializable
    data class ApprovalResponseDto(
        val requestId: String,
        val approved: Boolean,
        val approvingManagerId: String,
        val reason: String? = null,
    )

    /**
     * Request to approve an operation.
     *
     * @property requestId Request to approve
     */
    @Serializable
    data class ApproveRequestDto(
        val requestId: String,
    )

    /**
     * Request to deny an operation.
     *
     * @property requestId Request to deny
     * @property reason Reason for denial
     */
    @Serializable
    data class DenyRequestDto(
        val requestId: String,
        val reason: String,
    )

    /**
     * Status of an approval request.
     */
    @Serializable
    enum class RequestStatus {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED,
        CANCELLED,
    }

    /**
     * Request status response.
     *
     * @property requestId Request identifier
     * @property status Current status
     * @property response Response details if approved/denied
     */
    @Serializable
    data class RequestStatusDto(
        val requestId: String,
        val status: RequestStatus,
        val response: ApprovalResponseDto? = null,
    )

    /**
     * Notification sent to cashier after deferred action completes.
     */
    @Serializable
    data class ApprovalResultNotificationDto(
        val requestId: String,
        val operation: String,
        val approved: Boolean,
        val success: Boolean,
        val resultMessage: String,
        val errorCode: String? = null,
    )

    // ========== WebSocket Messages ==========

    /**
     * WebSocket message types for real-time authorization communication.
     */
    @Serializable
    sealed class WebSocketAuthMessage {
        /**
         * Notification of new approval request.
         */
        @Serializable
        data class ApprovalRequest(
            val request: ApprovalRequestDto,
        ) : WebSocketAuthMessage()

        /**
         * Response to approval request.
         */
        @Serializable
        data class ApprovalResponse(
            val response: ApprovalResponseDto,
        ) : WebSocketAuthMessage()

        /**
         * Warning that session is about to expire.
         */
        @Serializable
        data class SessionExpiring(
            val sessionId: String,
            val remainingSeconds: Int,
        ) : WebSocketAuthMessage()

        /**
         * Notification that session was created.
         */
        @Serializable
        data class SessionCreated(
            val session: TemporarySessionResponseDto,
        ) : WebSocketAuthMessage()

        /**
         * Notification that session ended.
         */
        @Serializable
        data class SessionEnded(
            val sessionId: String,
            val reason: String,
        ) : WebSocketAuthMessage()
    }
}
