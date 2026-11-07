/**
 * Ktor routes for print job history API endpoints.
 *
 * Provides REST API endpoint for querying print job audit trail:
 * - GET /api/print-job-history - Query print jobs by date range with optional filters
 *
 * All routes are protected with JWT authentication. Routes throw [PosException]
 * on parameter-validation failures; service errors propagate via StatusPages.
 */
package org.example.project.features.printer.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.printer.domain.service.PrintJobHistoryService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * Configures print job history routes.
 *
 * IMPORTANT: All routes are wrapped in a SINGLE authenticate block to prevent
 * "Default authentication configuration was not found" runtime errors.
 */
fun Route.printJobHistoryRoutes() {
    val printJobHistoryService by inject<PrintJobHistoryService>()

    // CRITICAL: Single authenticate wrapper for all routes
    authenticate("auth-jwt") {
        route("/api/print-job-history") {
            /**
             * List print jobs.
             *
             * Description: The print audit trail, used to prove a receipt was actually printed. Both `startDate` and `endDate` query parameters are required as ISO-8601 date-times. `status` (SUCCESS, FAILED or PENDING), `printType` (BILL, ORDER or ZBON) and `locationId` narrow the result, and `limit` defaults to 50.
             *
             * Tag: print-job-history
             * Response: 400 startDate or endDate is missing or not ISO-8601, or status or printType is not a known value.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val startDateStr =
                    call.request.queryParameters["startDate"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "startDate parameter is required",
                        )
                val endDateStr =
                    call.request.queryParameters["endDate"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "endDate parameter is required",
                        )

                val startDate =
                    try {
                        LocalDateTime.parse(startDateStr)
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid startDate format. Use ISO-8601: yyyy-MM-ddTHH:mm:ss",
                        )
                    }

                val endDate =
                    try {
                        LocalDateTime.parse(endDateStr)
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid endDate format. Use ISO-8601: yyyy-MM-ddTHH:mm:ss",
                        )
                    }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val status =
                    call.request.queryParameters["status"]?.let {
                        try {
                            PrinterDto.PrintJobStatus.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid status. Must be one of: SUCCESS, FAILED, PENDING",
                            )
                        }
                    }
                val printType =
                    call.request.queryParameters["printType"]?.let {
                        try {
                            PrinterDto.PrintType.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid printType. Must be one of: BILL, ORDER, ZBON",
                            )
                        }
                    }
                val locationId = call.request.queryParameters["locationId"]?.toLongOrNull()

                val jobs =
                    printJobHistoryService.getPrintJobsByDateRange(
                        startDate = startDate,
                        endDate = endDate,
                        limit = limit,
                        status = status,
                        printType = printType,
                        locationId = locationId,
                    )
                call.respond(HttpStatusCode.OK, jobs)
            }
        }
    }
}
