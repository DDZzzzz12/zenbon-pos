/**
 * Authorization extension functions for Ktor routes.
 *
 * Provides unified authorization handling with support for:
 * - Direct permission checking
 * - Temporary manager sessions (5-minute device-specific)
 * - Real-time approval requests (via WebSocket)
 *
 * Integrates with the hybrid authorization system to allow restricted
 * operations through multiple authorization paths.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils

import io.ktor.http.*
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.TokenClaims
import org.example.project.core.security.heldCapabilities
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.auth.domain.service.ApprovalRequestService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock

/**
 * Result of authorization check containing employee and manager context.
 *
 * Encapsulates the authorization path taken and provides context for
 * audit logging including both the requesting employee and authorizing manager.
 *
 * @property employeeId ID of the employee performing the operation
 * @property employeeName Name of the employee
 * @property employeeNumber Employee number
 * @property authorizationType How authorization was granted
 * @property authorizingManagerId Manager who authorized (null for DIRECT)
 * @property authorizingManagerName Manager name (null for DIRECT)
 * @property sessionId Temporary session ID (null for DIRECT and APPROVAL_REQUEST)
 * @property requestId Approval request ID (null for DIRECT and TEMPORARY_SESSION)
 */
data class AuthorizationResult(
    val employeeId: String,
    val employeeNumber: String,
    val authorizationType: TransactionAuditDto.AuthorizationType,
    val authorizingManagerId: String,
    val sessionId: String? = null,
    val requestId: String? = null,
)

/*
 * `AuthorizationResult.logTransaction(service, ...)` used to live here. It had NO CALL SITES — it
 * forwarded to `TransactionAuditService.logTransaction`, filling the employee and authorization
 * fields from the result, and every one of the six live audit writers injects the service and calls
 * it directly instead. `docs/claude/log-guideline.md` taught the forwarder as THE way to write an
 * audit row, which is the only reason it looked alive.
 *
 * Second instance of the pattern recorded below for `requirePermissionWithAuthorization`.
 */

/**
 * Exception thrown when authorization fails after all methods attempted.
 */
class InsufficientPermissionsException(
    val requiredCapability: Capability,
    val operation: String,
) : Exception("Insufficient permissions for operation: $operation (requires ${requiredCapability.name})")

/*
 * `requirePermissionWithAuthorization` used to live here. It had NO CALL SITES — every route that
 * defers uses `requirePermissionOrDefer` below — and it still carried a `isManagerOrAdmin(role)`
 * short-circuit that the capability migration never reached, because nothing exercised it. Its own
 * comment claimed "ORDER_ITEM_CANCEL is still unattached for exactly this reason", which stopped
 * being true when that capability was attached to the defer path.
 *
 * Dead authorization code is worse than dead code: it reads as a policy the system enforces.
 */

/**
 * Result indicating the action requires deferred approval.
 * The route should respond with 202 Accepted.
 */
data class DeferredApprovalResult(
    val requestId: String,
    val operation: String,
    val message: String = "Waiting for manager approval",
)

/**
 * Documents the two responses [requirePermissionOrDefer] writes itself on a route that calls it: 202 with the
 * approval request (`ApiResponseDto.SuccessResponse<AuthDto.ApprovalRequestDto>`) and 403 (`ApiResponseDto.ErrorResponse`).
 *
 * OpenAPI metadata only; the handler is untouched. The Ktor compiler plugin infers a route's responses from the
 * `respond(...)` calls it can see, and it follows a called function only inside the module it compiles. Since
 * this function moved to :platform-server (Stage B2-1b), routes in other modules lost these two schemas from
 * `/openapi-generated.json`; a KDoc `Response:` line cannot restore them, because the plugin resolves a KDoc type
 * as a top-level class (`ClassId.topLevel`) without type arguments. The descriptions stay in the route's KDoc:
 * the merge keeps the first non-empty one.
 */
fun Route.describeDeferredApprovalResponses(): Route =
    describe {
        responses {
            HttpStatusCode.Accepted {
                schema = jsonSchema<ApiResponseDto.SuccessResponse<AuthDto.ApprovalRequestDto>>()
            }
            HttpStatusCode.Forbidden {
                schema = jsonSchema<ApiResponseDto.ErrorResponse>()
            }
        }
    }

/**
 * Require permission with non-blocking deferred approval support.
 *
 * For Paths 0-2 (direct permission, temporary session): returns AuthorizationResult immediately.
 * For Path 3 (cashier needs approval): creates approval request, stores deferred action,
 * responds with 202 Accepted, and returns null. The caller should return immediately.
 *
 * @param requiredCapability The capability the operation needs
 * @param operation Operation name
 * @param details Additional context
 * @param deferredAction Lambda to execute if/when approved — returns a success message
 * @return AuthorizationResult if immediately authorized, null if deferred (202 already sent)
 */
suspend fun ApplicationCall.requirePermissionOrDefer(
    requiredCapability: Capability,
    operation: String,
    details: Map<String, String> = emptyMap(),
    deferredAction: suspend () -> String,
): AuthorizationResult? {
    val logger = LoggerFactory.getLogger("AuthorizationExtensions")

    val claims =
        getTokenClaims()
            ?: throw InsufficientPermissionsException(requiredCapability, operation)

    val employeeId = claims.employeeId
    val employeeNumber = claims.employeeNumber
    val role = claims.role
    val deviceId = claims.deviceId

    // Path 1: the caller holds the capability. ONE check now, where there used to be two.
    //
    // It replaces a role short-circuit (`isManagerOrAdmin`) and a permission check that had been
    // dead since Stage 2a (`claims.permissions` is permanently empty). `heldCapabilities()` is what
    // the route gate resolved for this very request, so this cannot disagree with it — and it
    // already includes anything an active step-up lent.
    val held = heldCapabilities()
    if (requiredCapability in held) {
        // A step-up is what makes the difference between DIRECT and TEMPORARY_SESSION here. It is
        // recorded rather than inferred: the audit line must say a cashier did this with a manager's
        // authority, not that a manager did it.
        val lending = activeStepUp(claims)
        return if (lending != null && requiredCapability in lending.grantedCapabilities && claims.station != null) {
            // An order station under a manager's temporary sign-in: the MANAGER performs the
            // correction, so the result names the manager as the actor (owner decision 2026-10-03,
            // emu21 NOTE-v; see `TokenClaims.overrideActorId()`). The routes pass
            // `authResult.employeeId` on to the service, which writes the audit row, the
            // cancellation/transfer record and the TSE transaction under it.
            AuthorizationResult(
                employeeId = lending.managerId,
                employeeNumber = employeeNumberOf(lending.managerId) ?: employeeNumber,
                authorizationType = TransactionAuditDto.AuthorizationType.TEMPORARY_SESSION,
                authorizingManagerId = lending.managerId,
                sessionId = lending.sessionId,
            )
        } else if (lending != null && requiredCapability in lending.grantedCapabilities) {
            AuthorizationResult(
                employeeId = employeeId,
                employeeNumber = employeeNumber,
                authorizationType = TransactionAuditDto.AuthorizationType.TEMPORARY_SESSION,
                authorizingManagerId = lending.managerId,
                sessionId = lending.sessionId,
            )
        } else {
            AuthorizationResult(
                employeeId = employeeId,
                employeeNumber = employeeNumber,
                authorizingManagerId = employeeId,
                authorizationType = TransactionAuditDto.AuthorizationType.DIRECT,
            )
        }
    }

    // Path 3: whoever reaches here lacks the capability, so they may REQUEST approval and a manager
    // decides.
    //
    // The condition is gone rather than converted. It read `!isManagerOrAdmin(role)`, and Path 1
    // above already returned for anyone holding the capability — so by here it was always true. It
    // had been a no-op for a different reason before that (the operands were reversed), and the
    // behaviour has been "everyone who reaches here defers" throughout. Now the code says so.
    //
    // A station never queues a Storno approval (owner decision 2026-10-03, emu21 NOTE-v). With no
    // manager online the request only expired after three minutes; instead a manager signs in on
    // the station (temporary session) and performs the Storno there. Refused BEFORE anything is
    // queued. Other deferrable operations — the item transfer — still defer on a station.
    if (claims.station != null && requiredCapability in STATION_UNREQUESTABLE) {
        logger.info("Refusing Storno approval request from station {} ({}): {}", deviceId, claims.station, operation)
        throw PosException(
            PosErrorCode.STATION_STORNO_REQUIRES_MANAGER,
            HttpStatusCode.Forbidden,
            "An order station cannot request a Storno approval. A manager must sign in on the station " +
                "(temporary session) and perform the Storno.",
        )
    }

    run {
        logger.info("Creating deferred approval request for $employeeNumber: $operation")

        val approvalService by inject<ApprovalRequestService>(ApprovalRequestService::class.java)
        val terminalService by inject<TerminalService>(TerminalService::class.java)

        // Resolve terminal name from deviceId
        val terminalName =
            try {
                terminalService.getTerminalByDeviceId(deviceId).terminalId
            } catch (_: Exception) {
                deviceId
            }

        val requestId =
            try {
                approvalService.createApprovalRequest(
                    employeeId = employeeId,
                    employeeNumber = employeeNumber,
                    deviceId = deviceId,
                    terminalName = terminalName,
                    operation = operation,
                    requiredCapabilities = setOf(requiredCapability),
                    details = details,
                )
            } catch (e: Exception) {
                logger.error("Failed to create approval request: ${e.message}")
                throw InsufficientPermissionsException(requiredCapability, operation)
            }

        // Store deferred action
        TemporarySessionStore.addDeferredAction(
            TemporarySessionStore.DeferredAction(
                requestId = requestId,
                employeeId = employeeId,
                deviceId = deviceId,
                operation = operation,
                actionExecutor = deferredAction,
            ),
        )

        // Respond with 202 Accepted
        respond(
            HttpStatusCode.Accepted,
            ApiResponseDto.success(
                message = "Approval request created. Waiting for manager approval.",
                data =
                    org.example.project.core.domain.dto.auth.AuthDto.ApprovalRequestDto(
                        requestId = requestId,
                        requestingEmployeeId = employeeId,
                        requestingEmployeeNumber = employeeNumber,
                        deviceId = deviceId,
                        terminalName = terminalName,
                        operation = operation,
                        requiredCapabilities = setOf(requiredCapability.name),
                        details = details,
                        timestamp = Clock.System.now().toString(),
                    ),
            ),
        )
        return null // Signals to caller: response already sent, do not continue
    }

    // Path 4: Denied
    respond(
        HttpStatusCode.Forbidden,
        ApiResponseDto.error(
            error = "Insufficient permissions. Manager authorization required.",
            details = listOf("Required: ${requiredCapability.name}"),
        ),
    )
    throw InsufficientPermissionsException(requiredCapability, operation)
}

/**
 * The capabilities whose approval an order station may not REQUEST — the Storno family.
 *
 * `ORDER_ITEM_CANCEL` is the only one a station can actually reach through
 * [requirePermissionOrDefer] today; `BILL_VOID` is listed so a future deferred bill Storno cannot
 * reopen the queue from a station by accident.
 */
private val STATION_UNREQUESTABLE: Set<Capability> = setOf(Capability.ORDER_ITEM_CANCEL, Capability.BILL_VOID)

/** The employee number for [employeeId], for the log line only; null when it cannot be resolved. */
private suspend fun employeeNumberOf(employeeId: String): String? =
    runCatching {
        dbQuery { Employee.findById(UUID.fromString(employeeId))?.employeeNumber }
    }.getOrNull()

/**
 * The step-up currently lending authority to this caller, or null.
 *
 * Device AND employee must match. Looking it up by device alone — as the old Path 2 did —
 * attributed every action on that till to the manager who happened to have stepped up, whoever
 * actually performed it.
 *
 * The rule itself lives in [TemporarySessionStore.activeElevationFor], the single definition
 * `elevatedCapabilities()` and `ExceptionConverter`'s audit writes also call. Three copies of three
 * conditions could drift, and the authorization gate and the audit trail would then disagree about
 * whether a manager was involved.
 */
private fun activeStepUp(claims: TokenClaims): TemporarySessionStore.SessionInfo? =
    TemporarySessionStore.activeElevationFor(employeeId = claims.employeeId, deviceId = claims.deviceId)
