/**
 * Business partner API routes — the company behind the creditor and debtor roles.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for the entire route group
 * - Never nest multiple authenticate blocks
 *
 * These live in `pos-server` rather than beside `SupplierRoutes` in `accounting-document`, because the
 * merge has to name the acting employee in its audit entry and `getEmployeeIdFromJwt()` is a
 * `pos-server` extension. `accounting-document` does not depend on `pos-server`, so a route there would
 * need a second copy of the claim extraction — two readings of "who is calling" is how the audit
 * trail comes to disagree with the gate.
 *
 * Endpoints:
 * - GET  /api/partners/duplicates  - Parties that are probably the same company
 * - GET  /api/partners/{id}        - One party, following a merge pointer to the survivor
 * - POST /api/partners/merge       - Declare two parties one company
 * - GET  /api/partners/export/datev-stammdaten - The Debitoren-/Kreditorenstamm CSV
 */
package org.example.project.features.partner.domain.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.partner.BusinessPartnerDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.partner.domain.service.BusinessPartnerService
import org.example.project.features.partner.domain.service.DatevStammdatenExportService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PartnerRoutes")

fun Route.partnerRoutes() {
    val service by inject<BusinessPartnerService>()
    val stammdatenExport by inject<DatevStammdatenExportService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/partners") {
            /**
             * Parties that are probably the same company.
             *
             * Description: Groups live parties that share a canonical VAT ID or a normalized name. Already-merged parties are excluded. This is a suggestion list — nothing here has been merged, and deciding two accounting identities are one company is the operator's judgement. Pages with `limit` (default 50, max 200).
             *
             * Tag: partners
             * Response: 200 The duplicate groups.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CREDITOR_READ.
             */
            get("/duplicates") {
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 200) ?: 50
                call.respond(service.duplicates(limit))
            }

            /**
             * One party.
             *
             * Description: Follows `merged_into_partner_id` to the survivor, so an id that was merged away still answers with the company it became rather than an empty shell.
             *
             * Tag: partners
             * Path: id Business partner identifier.
             * Response: 200 The party.
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CREDITOR_READ.
             * Response: 404 No party with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid id",
                        )
                val party =
                    service.get(id)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Business partner $id not found",
                        )
                call.respond(party)
            }

            /**
             * Declare two parties one company.
             *
             * Description: Moves the losing party's creditor role, debtor role and bank accounts onto the survivor, then keeps the losing row with a pointer to it so the decision stays reversible and stored references keep resolving. Bank accounts dedupe by IBAN. Refused when both parties carry a creditor role, or both a debtor role, because only one of each may. Writes a `MERGE_BUSINESS_PARTNER` audit entry.
             *
             * Tag: partners
             * Response: 204 Merged.
             * Response: 400 The two ids are the same, or one is already merged.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CREDITOR_WRITE and DEBTOR_WRITE — both are required.
             * Response: 404 One of the ids does not exist.
             * Response: 409 Both parties carry a creditor role (PARTNER_MERGE_CREDITOR_CONFLICT), or both a debtor role (PARTNER_MERGE_DEBTOR_CONFLICT).
             */
            post("/merge") {
                val request = call.receive<BusinessPartnerDto.MergeRequestDto>()
                service.merge(
                    survivorId = request.survivorId,
                    losingId = request.losingId,
                    actingEmployeeId = call.getEmployeeIdFromJwt(),
                )
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * The DATEV Debitoren-/Kreditorenstamm as a file.
             *
             * Description: Builds the master-data handover for every non-merged party of the shop and returns it as a CSV attachment — one row per Personenkonto, so a company we both buy from and sell to produces two rows that name each other in `Verknüpftes OPOS-Konto`. The file is **ISO-8859-1**, which is what DATEV reads; decoding it as UTF-8 corrupts every umlaut in it. Takes no parameters: Beraternummer, Mandantennummer, Sachkontenlänge and Sachkontenrahmen all come from the shop's own DATEV configuration, because a header field that depends on what a caller typed is one that can send a shop's whole master data to the wrong advisor.
             *
             * The export also reports accounts it left out (a role with no Personenkonto) and rows it wrote with a field dropped (a postcode DATEV's digits-only rule would reject). A CSV body cannot carry that report, so it rides on three response headers — `X-Export-Account-Count`, `X-Export-Skipped-Count`, `X-Export-Warning-Count` — and the detail behind the counts is logged at WARN. Headers rather than a second preview endpoint: this route is gated, so every caller reaches it through an authenticated request whose headers it can read, and the API is served same-origin with no CORS layer to strip them. A silently omitted creditor is exactly what must not first surface at the advisor's end, and a count of zero is as much a statement as a count of three — a client that shows the banner unconditionally cannot forget to ask.
             *
             * Tag: partners
             * Response: 200 The CSV file, `text/csv; charset=ISO-8859-1`.
             * Response: 400 The shop has no DATEV Berater- or Mandantennummer configured.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CREDITOR_READ and DEBTOR_READ — the file holds both kinds of master data, so both are required.
             * Response: 404 The shop record is missing.
             * Response: 409 No DATEV account mapping is configured, so the Sachkontenrahmen is unknown.
             */
            get("/export/datev-stammdaten") {
                val result = stammdatenExport.exportTransient()

                // WARN even when the file is fine: the counts say how many, the log says which
                // party and why, and a support call that arrives a week later has no other record.
                (result.skipped + result.warnings).forEach { issue ->
                    logger.warn("DATEV Stammdaten export: ${issue.code} for partner ${issue.partnerId} — ${issue.message}")
                }

                call.response.header("X-Export-Account-Count", result.accountCount.toString())
                call.response.header("X-Export-Skipped-Count", result.skipped.size.toString())
                call.response.header("X-Export-Warning-Count", result.warnings.size.toString())
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            // Named by the service, not by a literal here. The bytes and the name
                            // the operator files them under are one decision; two copies of it is
                            // how a file comes to be called something the format is not.
                            result.fileName,
                        ).toString(),
                )
                call.respondBytes(
                    bytes = result.bytes,
                    // The charset is part of the contract, not decoration: the builder writes
                    // ISO-8859-1 bytes, and a client that guesses UTF-8 renders "Müller" as
                    // mojibake or drops the row.
                    contentType = ContentType("text", "csv").withCharset(Charsets.ISO_8859_1),
                    status = HttpStatusCode.OK,
                )
            }
        }
    }
}
