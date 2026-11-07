/**
 * Implementation of ApprovalRequestService.
 *
 * Manages approval requests with WebSocket notifications and blocking waits.
 * Coordinates with in-memory store and notification manager for real-time
 * manager approval workflows.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, DataError> in Stage B Batch 7.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.AuthNotificationManager
import org.example.project.features.auth.data.DeferredActionExecutor
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.auth.domain.service.ApprovalDetailsResolver
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.inList
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Approval request service implementation.
 *
 * Handles approval request lifecycle including creation, status checking,
 * approval/denial, and blocking waits with timeout.
 */
@OptIn(ExperimentalTime::class)
class ApprovalRequestServiceImpl(
    private val resolvers: List<ApprovalDetailsResolver>,
) : ApprovalRequestService {
    private val logger = LoggerFactory.getLogger(ApprovalRequestServiceImpl::class.java)

    override suspend fun createApprovalRequest(
        employeeId: String,
        employeeNumber: String,
        deviceId: String,
        terminalName: String,
        operation: String,
        requiredCapabilities: Set<Capability>,
        details: Map<String, String>,
    ): String {
        // Validate input
        if (employeeId.isBlank() || operation.isBlank()) {
            throw validationFailed("employeeId and operation are required")
        }

        // Create unique request ID
        val requestId = "REQ-${UUID.randomUUID()}"
        val now = Clock.System.now()

        // Create pending request
        val request =
            TemporarySessionStore.PendingRequest(
                requestId = requestId,
                employeeId = employeeId,
                deviceId = deviceId,
                operation = operation,
                capabilities = requiredCapabilities,
                details = details,
                createdAt = now,
                status = AuthDto.RequestStatus.PENDING,
            )

        TemporarySessionStore.addRequest(request)

        logger.info(
            "createApprovalRequest: created approval request requestId={} for employeeId={}, operation={}",
            requestId,
            employeeId,
            operation,
        )

        // Broadcast approval request to all online managers via WebSocket. Assembled by the same
        // [toDto] the pending queue uses, so the live and the after-the-fact view cannot drift.
        AuthNotificationManager.broadcastApprovalRequest(
            toDto(request, employeeNumber = employeeNumber, terminalName = terminalName),
        )

        return requestId
    }

    override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> {
        val requests = TemporarySessionStore.getPendingRequests()
        if (requests.isEmpty()) return emptyList()

        // Resolved once for the whole queue, not once per request: the store keeps neither value.
        val employeeNumbers = employeeNumbersFor(requests.map { it.employeeId })
        val terminalNames = terminalNamesFor(requests.map { it.deviceId })

        return requests.map { request ->
            toDto(
                request = request,
                employeeNumber = employeeNumbers[request.employeeId].orEmpty(),
                terminalName = terminalNames[request.deviceId].orEmpty(),
            )
        }
    }

    override suspend fun approveRequest(
        requestId: String,
        managerId: String,
    ): AuthDto.ApprovalResponseDto {
        val request =
            TemporarySessionStore.getRequest(requestId)
                ?: run {
                    logger.warn("approveRequest: approval request not found, requestId={}", requestId)
                    throw approvalNotFound(requestId)
                }

        if (request.isExpired()) {
            logger.warn("approveRequest: request has expired, requestId={}", requestId)
            TemporarySessionStore.updateRequestStatus(requestId, AuthDto.RequestStatus.EXPIRED)
            throw approvalAlreadyHandled(requestId, "EXPIRED")
        }

        // Atomic claim — prevents double-approval by concurrent managers
        val claimed =
            TemporarySessionStore.claimRequest(
                requestId = requestId,
                newStatus = AuthDto.RequestStatus.APPROVED,
                managerId = managerId,
            )
        if (!claimed) {
            logger.warn(
                "approveRequest: request already processed, requestId={}, status={}",
                requestId,
                request.status,
            )
            throw approvalAlreadyHandled(requestId, request.status.name)
        }

        val response =
            AuthDto.ApprovalResponseDto(
                requestId = requestId,
                approved = true,
                approvingManagerId = managerId,
                reason = null,
            )

        // Send WebSocket response back to requesting device
        AuthNotificationManager.sendApprovalResponse(request.deviceId, response)

        // Execute the deferred action if one exists
        DeferredActionExecutor.executeApprovedAction(requestId)

        return response
    }

    override suspend fun denyRequest(
        requestId: String,
        managerId: String,
        reason: String,
    ): AuthDto.ApprovalResponseDto {
        val request =
            TemporarySessionStore.getRequest(requestId)
                ?: run {
                    logger.warn("denyRequest: approval request not found, requestId={}", requestId)
                    throw approvalNotFound(requestId)
                }

        // Atomic claim — prevents double-processing by concurrent managers
        val claimed =
            TemporarySessionStore.claimRequest(
                requestId = requestId,
                newStatus = AuthDto.RequestStatus.DENIED,
                managerId = managerId,
                denialReason = reason,
            )
        if (!claimed) {
            logger.warn(
                "denyRequest: request already processed, requestId={}, status={}",
                requestId,
                request.status,
            )
            throw approvalAlreadyHandled(requestId, request.status.name)
        }

        val response =
            AuthDto.ApprovalResponseDto(
                requestId = requestId,
                approved = false,
                approvingManagerId = managerId,
                reason = reason,
            )

        logger.info(
            "denyRequest: request denied, requestId={}, managerId={}, reason={}",
            requestId,
            managerId,
            reason,
        )

        // Send WebSocket response back to requesting device
        AuthNotificationManager.sendApprovalResponse(request.deviceId, response)

        // Notify cashier of denial via deferred action system
        DeferredActionExecutor.notifyDenied(requestId, reason)

        return response
    }

    override suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto {
        val request =
            TemporarySessionStore.getRequest(requestId)
                ?: throw approvalNotFound(requestId)

        val response =
            if (request.status == AuthDto.RequestStatus.APPROVED ||
                request.status == AuthDto.RequestStatus.DENIED
            ) {
                AuthDto.ApprovalResponseDto(
                    requestId = requestId,
                    approved = request.status == AuthDto.RequestStatus.APPROVED,
                    approvingManagerId = request.approvedBy ?: "",
                    reason = request.denialReason,
                )
            } else {
                null
            }

        return AuthDto.RequestStatusDto(
            requestId = requestId,
            status = request.status,
            response = response,
        )
    }

    override suspend fun waitForApproval(
        requestId: String,
        timeoutMillis: Long,
    ): AuthDto.ApprovalResponseDto {
        val startTime = System.currentTimeMillis()
        val pollInterval = 500L // Poll every 500ms

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val request =
                TemporarySessionStore.getRequest(requestId)
                    ?: run {
                        logger.warn("waitForApproval: request not found during wait, requestId={}", requestId)
                        throw approvalNotFound(requestId)
                    }

            when (request.status) {
                AuthDto.RequestStatus.APPROVED -> {
                    logger.info("waitForApproval: request was approved, requestId={}", requestId)
                    return AuthDto.ApprovalResponseDto(
                        requestId = requestId,
                        approved = true,
                        approvingManagerId = request.approvedBy ?: "",
                        reason = null,
                    )
                }

                AuthDto.RequestStatus.DENIED -> {
                    logger.info("waitForApproval: request was denied, requestId={}", requestId)
                    return AuthDto.ApprovalResponseDto(
                        requestId = requestId,
                        approved = false,
                        approvingManagerId = request.approvedBy ?: "",
                        reason = request.denialReason,
                    )
                }

                AuthDto.RequestStatus.EXPIRED -> {
                    logger.warn("waitForApproval: request expired during wait, requestId={}", requestId)
                    throw approvalAlreadyHandled(requestId, "EXPIRED")
                }

                AuthDto.RequestStatus.CANCELLED -> {
                    logger.info("waitForApproval: request was cancelled, requestId={}", requestId)
                    throw approvalAlreadyHandled(requestId, "CANCELLED")
                }

                AuthDto.RequestStatus.PENDING -> {
                    // Check if expired
                    if (request.isExpired()) {
                        TemporarySessionStore.updateRequestStatus(
                            requestId,
                            AuthDto.RequestStatus.EXPIRED,
                        )
                        logger.warn("waitForApproval: request expired, requestId={}", requestId)
                        throw approvalAlreadyHandled(requestId, "EXPIRED")
                    }
                    // Continue waiting
                    delay(pollInterval)
                }
            }
        }

        // Timeout reached
        logger.warn("waitForApproval: request timed out waiting for approval, requestId={}", requestId)
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.RequestTimeout,
            "Approval request $requestId timed out waiting for response",
        )
    }

    override suspend fun cancelRequest(requestId: String) {
        val request =
            TemporarySessionStore.getRequest(requestId)
                ?: throw approvalNotFound(requestId)

        if (request.status != AuthDto.RequestStatus.PENDING) {
            throw approvalAlreadyHandled(requestId, request.status.name)
        }

        TemporarySessionStore.updateRequestStatus(
            requestId,
            AuthDto.RequestStatus.CANCELLED,
        )

        logger.info("cancelRequest: request cancelled, requestId={}", requestId)
    }

    /**
     * Assemble the request a manager sees — for BOTH the live broadcast and the pending queue.
     *
     * The two used to be built at separate call sites and drifted apart: the queue rendered a bare
     * UUID instead of an employee number, a raw device id instead of a terminal name, and the
     * unresolved `itemIds`/`targetTableId` keys (E2E-2026-09-15 N5). One assembly point is what
     * stops that recurring.
     *
     * [TemporarySessionStore.PendingRequest] stores neither the employee number nor the terminal
     * name, so both are resolved by the caller and passed in. A blank value is deliberate and not
     * an error: the client falls back to the raw id (`ifBlank`), which still identifies the request,
     * whereas dropping it would hide an approval a manager is waiting on.
     */
    private suspend fun toDto(
        request: TemporarySessionStore.PendingRequest,
        employeeNumber: String,
        terminalName: String,
    ): AuthDto.ApprovalRequestDto =
        AuthDto.ApprovalRequestDto(
            requestId = request.requestId,
            requestingEmployeeId = request.employeeId,
            requestingEmployeeNumber = employeeNumber,
            deviceId = request.deviceId,
            terminalName = terminalName,
            operation = request.operation,
            requiredCapabilities = request.capabilities.map { it.name }.toSet(),
            details = resolveDetails(request.details),
            timestamp = request.createdAt.toString(),
        )

    /**
     * Employee numbers for [employeeIds], keyed by id — ONE query for the whole queue.
     *
     * An id that does not resolve (employee deleted, or a principal whose id is not a UUID) is
     * simply absent from the map; see [toDto] for why that degrades to the raw id rather than
     * failing.
     */
    private suspend fun employeeNumbersFor(employeeIds: List<String>): Map<String, String> {
        val uuids = employeeIds.distinct().mapNotNull { id -> runCatching { UUID.fromString(id) }.getOrNull() }
        if (uuids.isEmpty()) return emptyMap()
        return dbQuery {
            Employee
                .find { EmployeeTable.id inList uuids }
                .associate { it.id.value.toString() to it.employeeNumber }
        }
    }

    /**
     * Terminal names for [deviceIds], keyed by device id — ONE query for the whole queue.
     *
     * Reads `clientId` ("TER-001") because that is the field the live path shows: its caller passes
     * `TerminalService.getTerminalByDeviceId(...).terminalId`, and `TerminalMapper` maps that from
     * `Terminal.clientId`. The separate, nullable `Terminal.name` column would render a different
     * string here and put the queue back out of step with the broadcast.
     */
    private suspend fun terminalNamesFor(deviceIds: List<String>): Map<String, String> {
        val distinct = deviceIds.distinct().filter { it.isNotBlank() }
        if (distinct.isEmpty()) return emptyMap()
        return dbQuery {
            Terminal
                .find { TerminalTable.deviceId inList distinct }
                .mapNotNull { terminal -> terminal.deviceId?.let { device -> device to terminal.clientId } }
                .toMap()
        }
    }

    /**
     * Runs the registered resolvers one after another, each on the previous one's output. Unordered:
     * Koin's `getAll` returns map iteration order, not declaration order (`KoinGetAllOrderTest`), so a
     * second resolver that depends on another's output needs an explicit order key on the port.
     */
    private suspend fun resolveDetails(details: Map<String, String>): Map<String, String> = resolvers.fold(details) { acc, resolver -> resolver.resolve(acc) }

    // --- PosException helpers ---

    private fun approvalNotFound(requestId: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Approval request $requestId not found",
        )

    private fun approvalAlreadyHandled(
        requestId: String,
        status: String,
    ) = PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.Conflict,
        "Approval request $requestId is already $status",
    )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
