/**
 * Authorization notification manager for WebSocket messaging.
 *
 * Handles real-time notifications for the hybrid authorization system,
 * including approval requests, responses, and session updates.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.websocket

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isManagerOrHigher
import org.example.project.features.auth.data.TemporarySessionStore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Manages authorization-specific WebSocket notifications.
 *
 * Coordinates with NotificationHub to send targeted notifications
 * for approval requests, session updates, and authorization events.
 */
object AuthNotificationManager {
    private val logger = LoggerFactory.getLogger(AuthNotificationManager::class.java)

    // Track employee role for targeted broadcasting
    private val employeeRoles = ConcurrentHashMap<String, EmployeeRole>()

    // Queue for approval requests when no managers are online
    private val pendingApprovalQueue = ConcurrentLinkedQueue<AuthDto.ApprovalRequestDto>()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    /**
     * Register employee permissions for targeted notifications.
     *
     * @param employeeId Employee ID
     * @param role employee role
     */
    fun registerEmployeeRole(
        employeeId: String,
        role: EmployeeRole,
    ) {
        employeeRoles[employeeId] = role
        logger.debug("Registered role for employee $employeeId: $role")
    }

    /**
     * Unregister employee when disconnecting.
     *
     * @param employeeId Employee ID
     */
    fun unregisterEmployee(employeeId: String) {
        employeeRoles.remove(employeeId)
        logger.debug("Unregistered employee $employeeId")
    }

    /**
     * Broadcast approval request to all managers/admins.
     *
     * Sends notification to employees with MANAGER_ROLE, ADMIN_ROLE, or OWNER_ROLE.
     *
     * @param request Approval request to broadcast
     */
    suspend fun broadcastApprovalRequest(request: AuthDto.ApprovalRequestDto) {
        try {
            // Find employees with manager permissions (connected with their own account)
            val managerRoles =
                setOf(
                    EmployeeRole.MANAGER,
                    EmployeeRole.ADMIN,
                )

            val targetEmployees =
                employeeRoles
                    .filter { (_, role) ->
                        managerRoles.contains(role)
                    }.keys
                    .toMutableSet()

            // Also find devices with active temporary sessions where a manager is logged in.
            // The WebSocket connection is under the cashier's employeeId, so we send to that.
            val tempSessionTargets =
                TemporarySessionStore
                    .getActiveSessions()
                    .filter { it.managerRole.isManagerOrHigher() }
                    .map { it.requestingEmployeeId }
                    .filter { it !in targetEmployees } // avoid duplicate sends

            targetEmployees.addAll(tempSessionTargets)

            if (targetEmployees.isEmpty()) {
                logger.warn("No managers/admins online to receive approval request ${request.requestId} — queuing for delivery")
                pendingApprovalQueue.add(request)
                return
            }

            // Create WebSocket message
            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "APPROVAL_REQUEST",
                    data = json.encodeToString(request),
                )

            // Send to each target (managers + devices with temporary sessions)
            targetEmployees.forEach { employeeId ->
                NotificationHub.sendToEmployee(employeeId, message)
                logger.debug("Sent approval request ${request.requestId} to employee $employeeId")
            }

            logger.info(
                "Broadcasted approval request ${request.requestId} to ${targetEmployees.size} targets (including ${tempSessionTargets.size} temporary sessions)",
            )
        } catch (e: Exception) {
            logger.error("Error broadcasting approval request", e)
        }
    }

    /**
     * Send approval response back to requesting device.
     *
     * @param deviceId Device where request originated
     * @param response Approval response (approved or denied)
     */
    suspend fun sendApprovalResponse(
        deviceId: String,
        response: AuthDto.ApprovalResponseDto,
    ) {
        try {
            // Find employee by device ID (we need to track device-employee mapping)
            // For now, broadcast to all connections and let client filter by device ID
            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "APPROVAL_RESPONSE",
                    data = json.encodeToString(response),
                    targetDeviceId = deviceId,
                )

            NotificationHub.broadcastToAll(message)
            logger.info("Sent approval response for request ${response.requestId} to device $deviceId")
        } catch (e: Exception) {
            logger.error("Error sending approval response", e)
        }
    }

    /**
     * Send session expiring warning to device.
     *
     * @param deviceId Target device
     * @param sessionId Session identifier
     * @param remainingSeconds Seconds until expiration
     */
    suspend fun sendSessionExpiring(
        deviceId: String,
        sessionId: String,
        remainingSeconds: Int,
    ) {
        try {
            val warning =
                AuthDto.WebSocketAuthMessage.SessionExpiring(
                    sessionId = sessionId,
                    remainingSeconds = remainingSeconds,
                )

            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "SESSION_EXPIRING",
                    data = json.encodeToString(warning),
                    targetDeviceId = deviceId,
                )

            NotificationHub.broadcastToAll(message)
            logger.debug("Sent session expiring warning for $sessionId to device $deviceId")
        } catch (e: Exception) {
            logger.error("Error sending session expiring warning", e)
        }
    }

    /**
     * Notify device of session creation.
     *
     * @param deviceId Target device
     * @param session Session information
     */
    suspend fun notifySessionCreated(
        deviceId: String,
        session: AuthDto.TemporarySessionResponseDto,
    ) {
        try {
            val notification = AuthDto.WebSocketAuthMessage.SessionCreated(session = session)

            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "SESSION_CREATED",
                    data = json.encodeToString(notification),
                    targetDeviceId = deviceId,
                )

            NotificationHub.broadcastToAll(message)
            logger.info("Notified device $deviceId of session creation: ${session.sessionId}")
        } catch (e: Exception) {
            logger.error("Error notifying session created", e)
        }
    }

    /**
     * Notify device of session ending.
     *
     * @param deviceId Target device
     * @param sessionId Session identifier
     * @param reason Reason for ending
     */
    suspend fun notifySessionEnded(
        deviceId: String,
        sessionId: String,
        reason: String,
    ) {
        try {
            val notification =
                AuthDto.WebSocketAuthMessage.SessionEnded(
                    sessionId = sessionId,
                    reason = reason,
                )

            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "SESSION_ENDED",
                    data = json.encodeToString(notification),
                    targetDeviceId = deviceId,
                )

            NotificationHub.broadcastToAll(message)
            logger.info("Notified device $deviceId of session end: $sessionId")
        } catch (e: Exception) {
            logger.error("Error notifying session ended", e)
        }
    }

    /**
     * Deliver queued approval requests when a temporary session is created.
     * Sends to the device's cashier employee (who owns the WebSocket connection).
     *
     * @param cashierEmployeeId The cashier employee ID whose device has the WebSocket connection
     */
    suspend fun deliverQueuedApprovalsToTemporarySession(cashierEmployeeId: String) {
        deliverQueuedApprovalsToTarget(cashierEmployeeId)
    }

    /**
     * Deliver queued approval requests to a newly connected manager.
     * Called when a manager/admin connects via WebSocket.
     *
     * @param employeeId The newly connected manager's employee ID
     */
    suspend fun deliverQueuedApprovals(employeeId: String) {
        val role = employeeRoles[employeeId] ?: return
        val managerRoles = setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)
        if (!managerRoles.contains(role)) return

        deliverQueuedApprovalsToTarget(employeeId)
    }

    /**
     * Internal: deliver queued approval requests to a specific employee/device.
     */
    private suspend fun deliverQueuedApprovalsToTarget(targetEmployeeId: String) {
        logger.info("Attempting to deliver ${pendingApprovalQueue.size} queued approval requests to $targetEmployeeId")
        val delivered = mutableListOf<AuthDto.ApprovalRequestDto>()
        val iterator = pendingApprovalQueue.iterator()

        while (iterator.hasNext()) {
            val request = iterator.next()

            // Check if request is still valid (not expired — 3 min TTL)
            @OptIn(ExperimentalTime::class)
            val createdAtMs =
                try {
                    kotlin.time.Instant
                        .parse(request.timestamp)
                        .toEpochMilliseconds()
                } catch (_: Exception) {
                    request.timestamp.toLongOrNull() ?: 0L
                }
            val ageMs = System.currentTimeMillis() - createdAtMs
            if (ageMs > 180_000) {
                iterator.remove()
                logger.debug("Removed expired queued approval request ${request.requestId}")
                continue
            }

            val message =
                NotificationDto.AuthorizationNotificationDto(
                    timestamp = Clock.System.now().toString(),
                    notificationType = "APPROVAL_REQUEST",
                    data = json.encodeToString(request),
                )
            NotificationHub.sendToEmployee(targetEmployeeId, message)
            delivered.add(request)
            iterator.remove()
        }

        if (delivered.isNotEmpty()) {
            logger.info("Delivered ${delivered.size} queued approval requests to $targetEmployeeId")
        }
    }

    /**
     * Get notification statistics.
     *
     * @return Map with statistics
     */
    fun getStats(): Map<String, Any> =
        mapOf(
            "registeredEmployees" to employeeRoles.size,
            "managersOnline" to
                employeeRoles
                    .filter { (_, role) ->
                        role == EmployeeRole.MANAGER || role == EmployeeRole.ADMIN
                    }.size,
        )
}
