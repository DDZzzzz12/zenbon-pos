package org.example.project.features.datevexportlog.domain.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.ExportWarningHeaders
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportCatchupService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelBelegeExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.requireIsoDate
import org.koin.ktor.ext.inject
import java.util.UUID

fun Route.datevCadenceExportRoutes() {
    val catchupService by inject<DatevExportCatchupService>()
    val xmlExport by inject<DatevXmlExportService>()
    val apExport by inject<DatevApExportService>()
    val buchExport by inject<DatevBuchungsstapelExportService>()
    val bsBelegeExport by inject<DatevBuchungsstapelBelegeExportService>()

    authenticate("auth-jwt") {
        route("/api/v1/datev/export") {
            /**
             * Preview the DATEV catch-up export.
             *
             * Description: Requires MANAGER role. Reports which periods the configured cadence has left unexported, so an operator can see the gap before filling it. Both `type` (KASSENBUCH, AP_LEDGER or BUCHUNGSSTAPEL) and `cadence` (WEEKLY, MONTHLY or MANUAL) query parameters are required. BUCHUNGSSTAPEL_MIT_BELEGEN is ad-hoc only and AR_LEDGER not yet available, so neither has a catch-up.
             *
             * Tag: datev
             * Response: 400 type or cadence is missing or not a known value; or DATEV_EXPORT_TYPE_NOT_SCHEDULABLE — the type has no scheduled export (BUCHUNGSSTAPEL_MIT_BELEGEN, AR_LEDGER).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/catchup/preview") {
                val type =
                    call.request.queryParameters["type"]
                        ?.let { runCatching { DatevExportType.valueOf(it) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "type required (KASSENBUCH|AP_LEDGER|BUCHUNGSSTAPEL)",
                        )
                val cadence =
                    call.request.queryParameters["cadence"]
                        ?.let { runCatching { DatevExportCadence.valueOf(it) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "cadence required (WEEKLY|MONTHLY|MANUAL)",
                        )

                val preview = catchupService.preview(type, cadence)
                call.respond(HttpStatusCode.OK, preview)
            }

            /**
             * Run the DATEV catch-up export.
             *
             * Description: Requires MANAGER role. Exports every period the preview listed as missing and records each one in the export log, so the same period is not exported twice. Same required `type` and `cadence` query parameters as the preview. A period that cannot be exported does not fail the request: it is reported in `failed` with its reason — a DATEV export path that is not configured or not writable is one such reason (a configuration error, never a 503) — and the periods after it come back as `deferred`. The only exception is a paused export folder after a restore, which answers 409 RESTORED_PATHS_NEED_REVIEW.
             *
             * Tag: datev
             * Response: 400 type or cadence is missing or not a known value; or DATEV_EXPORT_TYPE_NOT_SCHEDULABLE — the type has no scheduled export (BUCHUNGSSTAPEL_MIT_BELEGEN, AR_LEDGER), refused before any period is exported.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable employee id.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 RESTORED_PATHS_NEED_REVIEW — the export folder came from a restored archive and has not been reviewed yet.
             */
            post("/catchup") {
                val type =
                    call.request.queryParameters["type"]
                        ?.let { runCatching { DatevExportType.valueOf(it) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "type required",
                        )
                val cadence =
                    call.request.queryParameters["cadence"]
                        ?.let { runCatching { DatevExportCadence.valueOf(it) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "cadence required",
                        )

                val tokenClaims =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.Unauthorized,
                            "Missing employeeId in JWT",
                        )
                val userId =
                    runCatching { UUID.fromString(tokenClaims.employeeId) }.getOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.Unauthorized,
                            "Invalid employeeId in JWT",
                        )

                val result = catchupService.execute(type, cadence, userId)
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Download a one-off DATEV export.
             *
             * Description: Requires MANAGER role. Builds an export for an arbitrary period and streams it straight back — for re-sending something to the accountant. The file is not written to the export folder, but every download is recorded in the export log as a MANUAL entry (period, file name, SHA-256 hash, counts, who exported it), so the log shows every file that left the system. Requires `type`, `startDate` and `endDate`; `excludeKassenbuchLinked` defaults to true for the AP ledger so documents already booked in the cash book are not exported twice. Buchungsstapel comes back as CSV, everything else as a ZIP. Lines exported without a contra account, because their account-mapping cell has automatic assignment switched off, are summarized in the `X-Export-Unassigned-Contra` header (percent-encoded JSON: line count and the cells concerned); the accountant then imports with "Buchungen trotz Fehler importieren" and completes the accounts in DATEV.
             *
             * Tag: datev
             * Response: 400 type, startDate or endDate is missing, or the type is AR_LEDGER, which is not yet available; or VALIDATION_FAILED — startDate or endDate is not a `yyyy-MM-dd` date (e.g. `06.05.2026`).
             * Response: 401 Missing or invalid bearer token, or the token carries no usable employee id.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 DATEV_ACCOUNT_MAPPING_REQUIRED — a Buchungsstapel export needs the DATEV account mapping, and it is missing or does not cover a line in the period.
             */
            get("/ad-hoc") {
                val type =
                    call.request.queryParameters["type"]
                        ?.let { runCatching { DatevExportType.valueOf(it) }.getOrNull() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "type required",
                        )
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")

                val tokenClaims =
                    call.getTokenClaims()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.Unauthorized,
                            "Missing employeeId in JWT",
                        )
                val userId =
                    runCatching { UUID.fromString(tokenClaims.employeeId) }.getOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.Unauthorized,
                            "Invalid employeeId in JWT",
                        )

                val excludeKassenbuchLinked =
                    call.request.queryParameters["excludeKassenbuchLinked"]
                        ?.toBooleanStrictOrNull() ?: true

                val result =
                    when (type) {
                        DatevExportType.KASSENBUCH -> xmlExport.exportTransient(startDate, endDate, userId)
                        DatevExportType.AP_LEDGER -> apExport.exportTransient(startDate, endDate, userId, excludeKassenbuchLinked)
                        DatevExportType.BUCHUNGSSTAPEL -> buchExport.exportTransient(startDate, endDate, userId)
                        DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN -> bsBelegeExport.exportTransient(startDate, endDate, userId)
                        DatevExportType.AR_LEDGER -> throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "AR_LEDGER export is not yet available",
                        )
                    }

                // A file body cannot carry the export's notices, so they ride on headers — the
                // same approach as the Stammdaten export's X-Export-*-Count. Without them the
                // Buchungsstapel period warnings reached no operator (E2E round 4, R4-4). The text
                // is percent-encoded UTF-8, one notice per line: header values must be ASCII.
                call.response.header(ExportWarningHeaders.COUNT, result.warnings.size.toString())
                if (result.warnings.isNotEmpty()) {
                    call.response.header(ExportWarningHeaders.TEXT, ExportWarningHeaders.encode(result.warnings))
                }
                // Lines left without a contra account (mapping switch „Konto automatisch zuordnen"
                // off) travel as structured JSON so the client can name the bookings localized.
                result.unassignedContra?.let {
                    call.response.header(ExportWarningHeaders.UNASSIGNED, ExportWarningHeaders.encodeUnassigned(it))
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            result.fileName,
                        ).toString(),
                )
                val contentType =
                    if (type == DatevExportType.BUCHUNGSSTAPEL) {
                        ContentType("text", "csv")
                    } else {
                        ContentType.Application.Zip
                    }
                call.respondBytes(result.bytes, contentType)
            }
        }
    }
}
