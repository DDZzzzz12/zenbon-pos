package org.example.project.features.auth.data

import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.NotificationHub
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Executes deferred actions after manager approval and notifies the cashier.
 */
object DeferredActionExecutor {
    private val logger = LoggerFactory.getLogger(DeferredActionExecutor::class.java)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Execute the deferred action for an approved request.
     * Sends APPROVAL_RESULT to the cashier's device via WebSocket.
     *
     * @param requestId The approved request ID
     */
    suspend fun executeApprovedAction(requestId: String) {
        val action = TemporarySessionStore.getDeferredAction(requestId)
        if (action == null) {
            logger.warn("No deferred action found for approved request $requestId")
            return
        }

        try {
            logger.info("Executing deferred action for request $requestId: ${action.operation}")
            // The audit row must name the approval this runs under; nothing else links the two.
            val resultMessage =
                approvalFor(requestId)
                    ?.let { withContext(it) { action.actionExecutor() } }
                    ?: action.actionExecutor()

            // Notify cashier of success
            sendApprovalResult(
                deviceId = action.deviceId,
                result =
                    AuthDto.ApprovalResultNotificationDto(
                        requestId = requestId,
                        operation = action.operation,
                        approved = true,
                        success = true,
                        resultMessage = resultMessage,
                    ),
            )

            logger.info("Deferred action completed successfully for request $requestId")
        } catch (e: Exception) {
            logger.error("Deferred action failed for request $requestId", e)

            // Notify cashier of failure
            sendApprovalResult(
                deviceId = action.deviceId,
                result =
                    AuthDto.ApprovalResultNotificationDto(
                        requestId = requestId,
                        operation = action.operation,
                        approved = true,
                        success = false,
                        resultMessage = e.message ?: "Action execution failed",
                        errorCode = "DEFERRED_ACTION_FAILED",
                    ),
            )
        } finally {
            TemporarySessionStore.removeDeferredAction(requestId)
        }
    }

    /**
     * The approval an approved request was claimed with, for the audit trail.
     *
     * Null only if the request is gone or was claimed without a manager — then the action still
     * runs, and its audit row degrades to what the actor's own session shows.
     */
    private fun approvalFor(requestId: String): ApprovalAuthorization? {
        val request = TemporarySessionStore.getRequest(requestId) ?: return null
        val managerId = request.approvedBy ?: return null
        return ApprovalAuthorization(
            requestId = requestId,
            managerId = managerId,
            managerName = request.approvedByName,
        )
    }

    /**
     * Notify cashier that request was denied.
     */
    suspend fun notifyDenied(
        requestId: String,
        reason: String?,
    ) {
        val action = TemporarySessionStore.getDeferredAction(requestId)
        if (action == null) {
            logger.warn("No deferred action found for denied request $requestId")
            return
        }

        sendApprovalResult(
            deviceId = action.deviceId,
            result =
                AuthDto.ApprovalResultNotificationDto(
                    requestId = requestId,
                    operation = action.operation,
                    approved = false,
                    success = false,
                    resultMessage = reason ?: "Request denied by manager",
                    errorCode = "APPROVAL_DENIED",
                ),
        )

        TemporarySessionStore.removeDeferredAction(requestId)
    }

    /**
     * Notify cashier that request expired.
     */
    suspend fun notifyExpired(requestId: String) {
        val action = TemporarySessionStore.getDeferredAction(requestId) ?: return

        sendApprovalResult(
            deviceId = action.deviceId,
            result =
                AuthDto.ApprovalResultNotificationDto(
                    requestId = requestId,
                    operation = action.operation,
                    approved = false,
                    success = false,
                    resultMessage = "Approval request expired",
                    errorCode = "APPROVAL_EXPIRED",
                ),
        )

        TemporarySessionStore.removeDeferredAction(requestId)
    }

    private suspend fun sendApprovalResult(
        deviceId: String,
        result: AuthDto.ApprovalResultNotificationDto,
    ) {
        val message =
            NotificationDto.AuthorizationNotificationDto(
                timestamp = Clock.System.now().toString(),
                notificationType = "APPROVAL_RESULT",
                data = json.encodeToString(result),
                targetDeviceId = deviceId,
            )
        NotificationHub.broadcastToAll(message)
        logger.info("Sent APPROVAL_RESULT for request ${result.requestId} to device $deviceId")
    }
}
