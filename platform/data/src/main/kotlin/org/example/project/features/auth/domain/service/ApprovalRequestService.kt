/**
 * Service interface for approval request management.
 *
 * Defines operations for creating, managing, and responding to approval requests
 * in the hybrid authorization system where cashiers request manager approval
 * for restricted operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 7.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.domain.service

import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability

/**
 * Approval request service interface.
 *
 * Manages the lifecycle of approval requests from creation to resolution,
 * including WebSocket notifications to managers and blocking waits for responses.
 */
interface ApprovalRequestService {
    /**
     * Create a new approval request for a restricted operation.
     *
     * Creates the request and triggers WebSocket notification to all connected
     * managers/admins for real-time approval.
     *
     * @return Request ID
     */
    suspend fun createApprovalRequest(
        employeeId: String,
        employeeNumber: String = "",
        deviceId: String,
        terminalName: String = "",
        operation: String,
        requiredCapabilities: Set<Capability>,
        details: Map<String, String>,
    ): String

    /**
     * Get all pending approval requests.
     *
     * Used by manager clients to display approval queue.
     */
    suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto>

    /**
     * Approve a pending request.
     *
     * Marks request as approved and sends WebSocket response to requesting device.
     */
    suspend fun approveRequest(
        requestId: String,
        managerId: String,
    ): AuthDto.ApprovalResponseDto

    /**
     * Deny a pending request.
     *
     * Marks request as denied and sends WebSocket response to requesting device.
     */
    suspend fun denyRequest(
        requestId: String,
        managerId: String,
        reason: String,
    ): AuthDto.ApprovalResponseDto

    /**
     * Get status of a specific request.
     *
     * Used for polling if WebSocket connection fails.
     */
    suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto

    /**
     * Wait for approval/denial of a request (blocking).
     *
     * Blocks until request is approved, denied, or times out.
     * Used by requesting employee's client after creating request.
     */
    suspend fun waitForApproval(
        requestId: String,
        timeoutMillis: Long = 300000,
    ): AuthDto.ApprovalResponseDto

    /**
     * Cancel a pending request.
     *
     * Used if employee cancels the operation before approval.
     */
    suspend fun cancelRequest(requestId: String)
}
