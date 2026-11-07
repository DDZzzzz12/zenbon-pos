/**
 * REST API routes for the DATEV exports of the Kassenbuch (cash book).
 *
 * Split out of `KassenbuchRoutes` so the accounting exports live apart from cash-book booking.
 * Paths, capability checks (declared in RoutePermissions) and error mapping are unchanged.
 */
package org.example.project.features.kassenbuch.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.requireIsoDate
import org.example.project.utils.toIdListOrNull
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("KassenbuchRoutes")

fun Route.datevKassenbuchRoutes() {
    val datevXmlExportService by inject<DatevXmlExportService>()
    val datevBuchungsstapelExportService by inject<DatevBuchungsstapelExportService>()

    authenticate("auth-jwt") {
        route("/api/v1/kassenbuch") {
            /**
             * Export the cash book as DATEV XML.
             *
             * Description: Requires the capability RoutePermissions names for this route. Produces the DATEV XML Schnittstelle online ZIP for the accountant. `startDate` and `endDate` are required; `periodLabel` names the period in the archive, and `cashBookIds` takes a comma-separated list. Omitting `cashBookIds` exports every book — the default must never silently narrow. The export is recorded in the DATEV export log with source MANUAL, so it does not count as the scheduled export of that period.
             *
             * Tag: kassenbuch
             * Response: 400 startDate or endDate is missing, or VALIDATION_FAILED — one of them is not a `yyyy-MM-dd` date, or the DATEV export path is not configured or not a writable directory (bad configuration, so a 400 — not a 503).
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/export/datev-xml") {
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")
                val periodLabel = call.request.queryParameters["periodLabel"] ?: ""
                val cashBookIds = call.request.queryParameters["cashBookIds"].toIdListOrNull()

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val exportedByUserId = UUID.fromString(tokenClaims.employeeId)

                logger.info("Exporting Kassenbuch entries to DATEV XML for date range: $startDate to $endDate")

                call.respond(
                    HttpStatusCode.OK,
                    datevXmlExportService.export(
                        startDate,
                        endDate,
                        exportedByUserId,
                        periodLabel,
                        // An operator asked for this file: MANUAL, so it neither shows as "Automatisch"
                        // in the export log nor advances the catch-up cursor (T9-16).
                        source = DatevExportSource.MANUAL,
                        cashBookIds = cashBookIds,
                    ),
                )
            }

            /**
             * Export the cash book as a DATEV Buchungsstapel.
             *
             * Description: Requires the capability RoutePermissions names for this route. The batch-posting format for local DATEV Rechnungswesen, as opposed to the online XML. Same parameters and same all-books default as the XML export; recorded in the export log with source MANUAL.
             *
             * Tag: kassenbuch
             * Response: 400 startDate or endDate is missing, or VALIDATION_FAILED — one of them is not a `yyyy-MM-dd` date, or the DATEV export path is not configured or not a writable directory (bad configuration, so a 400 — not a 503).
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/export/buchungsstapel") {
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")
                val periodLabel = call.request.queryParameters["periodLabel"] ?: ""
                val cashBookIds = call.request.queryParameters["cashBookIds"].toIdListOrNull()

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val exportedByUserId = UUID.fromString(tokenClaims.employeeId)

                logger.info("Exporting Kassenbuch entries to DATEV Buchungsstapel for date range: $startDate to $endDate")

                call.respond(
                    HttpStatusCode.OK,
                    datevBuchungsstapelExportService.export(
                        startDate,
                        endDate,
                        exportedByUserId,
                        periodLabel,
                        // An operator asked for this file: MANUAL, so it neither shows as "Automatisch"
                        // in the export log nor advances the catch-up cursor (T9-16).
                        source = DatevExportSource.MANUAL,
                        cashBookIds = cashBookIds,
                    ),
                )
            }

            /**
             * Preview the DATEV XML export.
             *
             * Description: Requires the capability RoutePermissions names for this route. Entry count, day count and total for the period, so the operator can sanity-check before exporting. It honours the same `cashBookIds` selection as the export itself — ignoring it here would preview all books and then deliver only the selected ones.
             *
             * Tag: kassenbuch
             * Response: 400 startDate or endDate is missing, or VALIDATION_FAILED — one of them is not a `yyyy-MM-dd` date.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/export/datev-xml/statistics") {
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")

                // Same selection the export itself accepts: this endpoint is the export's
                // PREVIEW, so ignoring cashBookIds here would show the operator totals for all
                // books and then hand them a file containing only the selected ones.
                val cashBookIds = call.request.queryParameters["cashBookIds"].toIdListOrNull()

                val stats = datevXmlExportService.getStatistics(startDate, endDate, cashBookIds)

                // Map the unified DatevExportStatisticsDto back to the legacy
                // DatevXmlExportDto.ExportStatisticsDto on the wire — existing
                // HTTP clients still deserialize into the legacy shape (field
                // name `totalAmount`, not `totalAmountFormatted`). The client
                // migration lives in a later phase.
                @Suppress("DEPRECATION")
                val legacy =
                    DatevXmlExportDto.ExportStatisticsDto(
                        totalEntries = stats.totalEntries,
                        totalDays = stats.totalDays,
                        totalAmount = stats.totalAmountFormatted,
                        dateRange = stats.dateRange,
                    )
                call.respond(HttpStatusCode.OK, legacy)
            }
        }
    }
}
