/**
 * TSE Signing Queue API routes for monitoring and management.
 *
 * Provides JWT-protected REST endpoints for managing the TSE signing queue,
 * which implements "Nachsignieren" (late signing) for German KassenSichV
 * compliance. Routes contain no business logic — the global StatusPages
 * plugin handles every PosException thrown by services.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.service.TseSigningRetryService
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TseSigningQueueRoutes")

@Serializable
private data class CleanupRequestDto(
    val olderThanDays: Int = 30,
) {
    fun validate() {
        require(olderThanDays in 1..365) { "olderThanDays must be between 1 and 365" }
    }
}

fun Route.tseSigningQueueRoutes() {
    val queueService by inject<TseSigningQueueService>()

    authenticate("auth-jwt") {
        route("/api/tse/queue") {
            // ========================================
            // Status & Monitoring Endpoints
            // ========================================

            /**
             * Get the signing-queue status.
             *
             * Description: Requires ADMIN role. Summary of the Nachsignieren queue — how many receipts are waiting, failed or done, and whether the retry service is running. A non-empty pending count means receipts exist that the TSE has not yet signed.
             *
             * Tag: tse-queue
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/status") {
                logger.debug("GET /api/tse/queue/status - Fetching queue status")
                call.respond(HttpStatusCode.OK, queueService.getQueueStatus())
            }

            /**
             * List signing-queue entries.
             *
             * Description: Requires ADMIN role. Optional `status` and `entityType` query parameters filter the list to one signing status or entity kind. `limit` must be 1–1000 (default 100) and `offset` must not be negative (default 0).
             *
             * Tag: tse-queue
             * Response: 400 status or entityType is not a known value, limit is outside 1–1000, or offset is negative.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/entries") {
                logger.debug("GET /api/tse/queue/entries - Listing queue entries")

                val statusParam = call.request.queryParameters["status"]
                val entityTypeParam = call.request.queryParameters["entityType"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val status =
                    statusParam?.let {
                        try {
                            TseSigningStatus.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                code = PosErrorCode.VALIDATION_FAILED,
                                httpStatus = HttpStatusCode.BadRequest,
                                message = "Invalid status: $it. Valid values: ${TseSigningStatus.entries.joinToString()}",
                            )
                        }
                    }

                val entityType =
                    entityTypeParam?.let {
                        try {
                            TseEntityType.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                code = PosErrorCode.VALIDATION_FAILED,
                                httpStatus = HttpStatusCode.BadRequest,
                                message = "Invalid entityType: $it. Valid values: ${TseEntityType.entries.joinToString()}",
                            )
                        }
                    }

                if (limit !in 1..1000) {
                    throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = "Limit must be between 1 and 1000",
                    )
                }

                if (offset < 0) {
                    throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = "Offset must be non-negative",
                    )
                }

                call.respond(HttpStatusCode.OK, queueService.listEntries(status, entityType, limit, offset))
            }

            /**
             * Get a signing-queue entry.
             *
             * Description: Requires ADMIN role. The full record for one queued signature, including its retry count and the last failure reason.
             *
             * Tag: tse-queue
             * Path: id Queue entry identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No queue entry with that id.
             */
            get("/entries/{id}") {
                val entryId =
                    call.parameters["id"]?.toLongOrNull() ?: throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = "Invalid entry ID",
                    )
                logger.debug("GET /api/tse/queue/entries/$entryId - Fetching entry")
                call.respond(HttpStatusCode.OK, queueService.getEntry(entryId))
            }

            // ========================================
            // Manual Operation Endpoints
            // ========================================

            /**
             * Retry a queued signature now.
             *
             * Description: Requires ADMIN role. Signs the entry immediately instead of waiting for the retry service's next cycle — what an operator does once a TSE fault has been fixed.
             *
             * Tag: tse-queue
             * Path: id Queue entry identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No queue entry with that id.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the signature or the device could not be reached.
             */
            post("/entries/{id}/retry") {
                val entryId =
                    call.parameters["id"]?.toLongOrNull() ?: throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = "Invalid entry ID",
                    )
                logger.info("POST /api/tse/queue/entries/$entryId/retry - Manual retry requested")
                call.respond(HttpStatusCode.OK, queueService.retryEntry(entryId))
            }

            /**
             * Abandon a queued signature.
             *
             * Description: Requires ADMIN role. The outcome is a Vorgang that stays permanently unsigned under §146a AO, so a reason is mandatory and the acting employee is recorded from the JWT. Use it only when the receipt can genuinely never be signed.
             *
             * Tag: tse-queue
             * Path: id Queue entry identifier.
             * Response: 400 id is not a number, or no reason was given.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No queue entry with that id.
             */
            post("/entries/{id}/cancel") {
                val entryId =
                    call.parameters["id"]?.toLongOrNull() ?: throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = "Invalid entry ID",
                    )
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                // M8: abandoning a signature leaves the Beleg permanently unsigned, so who and why
                // are recorded. The reason comes from the operator, not a default.
                val reason =
                    runCatching { call.receive<TseSigningQueueDto.CancelEntryRequestDto>() }
                        .getOrNull()
                        ?.reason
                        .orEmpty()
                logger.info("POST /api/tse/queue/entries/$entryId/cancel - Cancellation requested by ${tokenClaims.employeeId}")
                call.respond(
                    HttpStatusCode.OK,
                    queueService.cancelEntry(entryId, tokenClaims.employeeId, reason),
                )
            }

            // ========================================
            // Service Control Endpoints
            // ========================================

            /**
             * Pause the retry service.
             *
             * Description: Requires ADMIN role. Stops the background Nachsignieren cycle — for TSE maintenance, so the queue does not keep hammering a device that is deliberately offline. Queued entries stay queued.
             *
             * Tag: tse-queue
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 503 The retry service is not initialized.
             */
            post("/pause") {
                logger.info("POST /api/tse/queue/pause - Pausing retry service")

                val retryService = TseSigningRetryService.getInstance()
                if (retryService == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        TseSigningQueueDto.ServiceControlResponseDto(
                            success = false,
                            serviceRunning = false,
                            message = "Retry service not initialized",
                        ),
                    )
                    return@post
                }

                retryService.pause()
                call.respond(
                    HttpStatusCode.OK,
                    TseSigningQueueDto.ServiceControlResponseDto(
                        success = true,
                        serviceRunning = TseSigningRetryService.isRunning(),
                        message = "Retry service paused",
                    ),
                )
            }

            /**
             * Resume the retry service.
             *
             * Description: Requires ADMIN role. Restarts the background Nachsignieren cycle after a pause.
             *
             * Tag: tse-queue
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 503 The retry service is not initialized.
             */
            post("/resume") {
                logger.info("POST /api/tse/queue/resume - Resuming retry service")

                val retryService = TseSigningRetryService.getInstance()
                if (retryService == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        TseSigningQueueDto.ServiceControlResponseDto(
                            success = false,
                            serviceRunning = false,
                            message = "Retry service not initialized",
                        ),
                    )
                    return@post
                }

                retryService.resume()
                call.respond(
                    HttpStatusCode.OK,
                    TseSigningQueueDto.ServiceControlResponseDto(
                        success = true,
                        serviceRunning = TseSigningRetryService.isRunning(),
                        message = "Retry service resumed",
                    ),
                )
            }

            /**
             * Run a retry cycle now.
             *
             * Description: Requires ADMIN role. Runs one Nachsignieren pass immediately over every eligible entry, rather than waiting for the scheduled cycle.
             *
             * Tag: tse-queue
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 503 The retry service is not initialized.
             */
            post("/trigger") {
                logger.info("POST /api/tse/queue/trigger - Manual retry cycle triggered")

                val retryService =
                    TseSigningRetryService.getInstance() ?: throw PosException(
                        code = PosErrorCode.TSE_NOT_CONFIGURED,
                        httpStatus = HttpStatusCode.ServiceUnavailable,
                        message = "Retry service not initialized",
                    )
                call.respond(HttpStatusCode.OK, retryService.triggerManualRetry().toResponseDto())
            }

            // ========================================
            // Maintenance Endpoints
            // ========================================

            /**
             * Delete old completed queue entries.
             *
             * Description: Requires ADMIN role. Housekeeping for entries already signed. `olderThanDays` must be 1–365 and defaults to 30; an unparseable body falls back to that default rather than failing. Only completed entries are removed — nothing still awaiting a signature is touched.
             *
             * Tag: tse-queue
             * Response: 400 olderThanDays is outside 1–365.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            post("/cleanup") {
                logger.info("POST /api/tse/queue/cleanup - Cleanup requested")

                val request =
                    try {
                        call.receive<CleanupRequestDto>()
                    } catch (e: Exception) {
                        CleanupRequestDto()
                    }

                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = e.message ?: "Validation failed",
                        cause = e,
                    )
                }

                val deleted = queueService.cleanupOldEntries(request.olderThanDays)
                call.respond(
                    HttpStatusCode.OK,
                    TseSigningQueueDto.CleanupResultDto(
                        deletedCount = deleted,
                        olderThanDays = request.olderThanDays,
                    ),
                )
            }
        }
    }
}
