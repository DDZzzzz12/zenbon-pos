/**
 * DSFinV-K export API routes.
 *
 * Provides REST endpoints for German fiscal compliance (DSFinV-K) CSV export
 * including export generation, validation, and statistics. All endpoints
 * require MANAGER role or higher for access.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dsfinvk.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DsfinvkExportRoutes")

/**
 * DSFinV-K export API routes.
 *
 * Provides endpoints for German fiscal compliance CSV export:
 * - POST /api/v1/dsfinvk/export - Generate and download export
 * - GET /api/v1/dsfinvk/validate - Validate data before export
 * - GET /api/v1/dsfinvk/statistics - Get export statistics
 *
 * All endpoints require MANAGER role or higher.
 */
fun Route.dsfinvkExportRoutes() {
    val service by inject<DsfinvkExportService>()

    authenticate("auth-jwt") {
        route("/api/v1/dsfinvk") {
            /**
             * Generate a DSFinV-K export.
             *
             * Description: Requires the capability RoutePermissions names for this route. Builds the complete DSFinV-K CSV package for the requested period and returns it as a ZIP attachment — the archive handed to a tax auditor. Large periods make this slow, since every transaction and line item is written out.
             *
             * Tag: dsfinvk
             * Response: 400 The request body is malformed, or the period failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 500 The export could not be assembled.
             */
            post("/export") {
                logger.info("Received DSFinV-K export request")

                val request =
                    try {
                        call.receive<DsfinvkExportDto.ExportRequestDto>()
                    } catch (e: Exception) {
                        logger.warn("Invalid export request: ${e.message}")
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request body: ${e.message}",
                        )
                    }

                logger.info("Generating DSFinV-K export for period: ${request.startDate} to ${request.endDate}")

                val bytes = service.generateExport(request)
                val fileName = "dsfinvk_export_${request.startDate}_${request.endDate}.zip"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            fileName,
                        ).toString(),
                )
                call.respondBytes(
                    bytes = bytes,
                    contentType = ContentType.Application.Zip,
                    status = HttpStatusCode.OK,
                )
            }

            /**
             * Validate DSFinV-K data before exporting.
             *
             * Description: Requires the capability RoutePermissions names for this route. Runs the pre-export checks — missing TSE signatures, unclosed Z-reports and similar defects — and reports what would make the export non-compliant. Both `startDate` and `endDate` query parameters are required, in ISO date form.
             *
             * Tag: dsfinvk
             * Response: 400 startDate or endDate is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/validate") {
                val startDate =
                    call.request.queryParameters["startDate"]
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "startDate query parameter is required",
                        )
                val endDate =
                    call.request.queryParameters["endDate"]
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "endDate query parameter is required",
                        )

                logger.info("Validating DSFinV-K export data for period: $startDate to $endDate")
                val result = service.validateExportData(startDate, endDate)
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Preview the DSFinV-K export.
             *
             * Description: Requires the capability RoutePermissions names for this route. Counts and totals for Z-reports, transactions and line items in the period, without building the archive. Both `startDate` and `endDate` query parameters are required.
             *
             * Tag: dsfinvk
             * Response: 400 startDate or endDate is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/statistics") {
                val startDate =
                    call.request.queryParameters["startDate"]
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "startDate query parameter is required",
                        )
                val endDate =
                    call.request.queryParameters["endDate"]
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "endDate query parameter is required",
                        )

                logger.info("Getting DSFinV-K export statistics for period: $startDate to $endDate")
                val result = service.getExportStatistics(startDate, endDate)
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}
