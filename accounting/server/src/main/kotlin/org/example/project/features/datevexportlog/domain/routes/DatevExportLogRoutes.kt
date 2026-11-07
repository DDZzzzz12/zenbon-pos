package org.example.project.features.datevexportlog.domain.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.datevExportLogRoutes() {
    val service by inject<DatevExportLogService>()
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/v1/datev/export-log") {
            /**
             * List DATEV export log entries.
             *
             * Description: Requires MANAGER role. The record of which periods have already been exported, which is what stops the same period going to the accountant twice. Both `periodStart` and `periodEnd` are required as ISO date-times and match by overlap; `exportType` narrows to one export kind. Rows of every source are returned — the scheduled (AUTO) ones and those an operator requested (MANUAL) — unless `source` narrows to one; the catch-up coverage check reads AUTO rows only, through its own query.
             *
             * Tag: datev
             * Response: 400 periodStart or periodEnd is missing or not an ISO date-time, or source is neither AUTO nor MANUAL.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val periodStart =
                    call.request.queryParameters["periodStart"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val periodEnd =
                    call.request.queryParameters["periodEnd"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val exportTypeParam =
                    call.request.queryParameters["exportType"]
                        ?.let { runCatching { DatevExportType.valueOf(it) }.getOrNull() }

                // Absent = every source (T9-16). The service's AUTO default exists for coverage checks;
                // the log a back-office user reads must also show what they exported by hand.
                val sourceFilter =
                    call.request.queryParameters["source"]?.let { raw ->
                        DatevExportSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                            ?: throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "source must be AUTO or MANUAL",
                            )
                    }

                if (periodStart == null || periodEnd == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "periodStart and periodEnd required (ISO LocalDateTime)",
                    )
                }

                val logs = service.findOverlapping(periodStart, periodEnd, exportTypeParam, sourceFilter = sourceFilter)
                call.respond(HttpStatusCode.OK, logs)
            }

            /**
             * Download a logged DATEV export.
             *
             * Description: Requires MANAGER role. Re-serves the archived file from the configured export path, looking first in the period folder and then flat in the export root, which is where exports written before the period-folder change still live.
             *
             * Tag: datev
             * Path: id Export log entry identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No log entry with that id, or its file is missing from disk.
             * Response: 503 The DATEV export path is not configured.
             */
            get("/{id}/download") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a Long",
                        )

                val exportPath = configManager.getDatevXmlExportPath()
                if (exportPath.isBlank()) {
                    throw PosException(
                        PosErrorCode.INTERNAL_ERROR,
                        HttpStatusCode.ServiceUnavailable,
                        "Server export path not configured",
                    )
                }

                val log = service.findById(id)
                // Resolve in order: <exportPath>/<periodFolder>/<fileName>, then flat <exportPath>/<fileName>
                // (flat fallback handles old log rows from before the periodFolder feature).
                val nested = java.io.File(java.io.File(exportPath, log.periodFolder), log.fileName)
                val flat = java.io.File(exportPath, log.fileName)
                val file =
                    when {
                        nested.exists() -> nested
                        flat.exists() -> flat
                        else -> throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "File missing on disk: ${log.fileName}",
                        )
                    }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            log.fileName,
                        ).toString(),
                )
                call.respondFile(file)
            }
        }
    }
}
