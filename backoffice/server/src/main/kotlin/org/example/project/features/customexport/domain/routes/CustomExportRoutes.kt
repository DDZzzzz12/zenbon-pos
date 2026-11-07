/**
 * Custom export API routes — the operator-defined extracts and the catalogue they are built from.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for the entire route group
 * - Never nest multiple authenticate blocks
 *
 * Capabilities come from `call.heldCapabilities()` — the set the authorization plugin already
 * resolved for this call — rather than being resolved a second time here. That is the point: the
 * catalogue a caller is *offered* and the gate that *admits* them are then two readings of one
 * decision, and cannot disagree. A second resolution is how the client and server hierarchies
 * drifted apart in defect D2.
 *
 * Endpoints:
 * - GET    /api/custom-exports/catalogue - Subjects and fields as this caller may see them
 * - GET    /api/custom-exports           - The shop's saved definitions
 * - POST   /api/custom-exports           - Save a definition
 * - PUT    /api/custom-exports/{id}      - Replace one in place
 * - DELETE /api/custom-exports/{id}      - Delete one
 * - GET    /api/custom-exports/{id}/run  - Run it, returning the CSV
 */
package org.example.project.features.customexport.domain.routes

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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.heldCapabilities
import org.example.project.features.customexport.domain.service.CustomExportService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

fun Route.customExportRoutes() {
    val service by inject<CustomExportService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/custom-exports") {
            /**
             * What this caller may build an export of.
             *
             * Description: Returns the subjects, their fields and their pre-written filters, already narrowed to the caller's own capabilities — a subject whose every field the caller may not see is omitted entirely rather than returned empty. The filtering uses the capability set the authorization plugin resolved for this request, so the catalogue can never offer a field the save and run checks would then refuse. It is a convenience for the client, not the control: the server checks again on save and again on every run.
             *
             * Tag: custom-exports
             * Response: 200 The catalogue, possibly an empty list when the caller may see no field of any subject.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_READ.
             */
            get("/catalogue") {
                call.respond(service.catalogue(call.heldCapabilities()))
            }

            /**
             * The shop's saved export definitions.
             *
             * Description: Every definition of the current shop, name-ordered, each with its columns in position order. A definition the catalogue can no longer resolve is returned flagged `isBroken` with the keys at fault rather than hidden, so the operator can see what to fix or delete. The list is not filtered by capability — a definition's existence is not itself sensitive, and running it is where the field check bites.
             *
             * Tag: custom-exports
             * Response: 200 The saved definitions.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_READ.
             */
            get {
                call.respond(service.list())
            }

            /**
             * Saves a new export definition.
             *
             * Description: Validates the whole request against today's catalogue and against the caller's own capabilities before anything is stored — an unknown subject, an unknown field or toggle key, two columns claiming one position, a date range against a subject that has no date column, or a field the caller may not see are all refused. Nothing is persisted that we already know is wrong. The acting employee is taken from the token, never from the body.
             *
             * Tag: custom-exports
             * Response: 200 The stored definition.
             * Response: 400 The request names something the catalogue does not know, or is otherwise malformed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_MANAGE.
             * Response: 404 No shop is configured, so a definition has nothing to belong to.
             */
            post {
                val request = call.receive<CustomExportDto.SaveDefinitionRequestDto>()
                call.respond(
                    service.save(
                        request = request,
                        held = call.heldCapabilities(),
                        employeeId = call.getEmployeeIdFromJwt(),
                    ),
                )
            }

            /**
             * Replaces a saved export definition.
             *
             * Description: Overwrites the definition's name, subject, columns, date range and toggles in place, keeping its id, its shop and the employee who first saved it. The body is the same as for saving a new definition and is validated identically — against today's catalogue and against the caller's own capabilities, including the aggregate cap and the suppressed per-person aggregates — so an edit can never store what a fresh save would refuse. The subject cannot change — it defines what one row of the file is, so a different subject is a new export. The columns are replaced wholesale, not merged.
             *
             * Tag: custom-exports
             * Path: id Export definition identifier.
             * Response: 200 The updated definition.
             * Response: 400 The id is not a number, the request changes the subject, names something the catalogue does not know, or is otherwise malformed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_MANAGE.
             * Response: 404 No definition with that id.
             */
            put("/{id}") {
                val id = definitionId(call.parameters["id"])
                val request = call.receive<CustomExportDto.SaveDefinitionRequestDto>()
                call.respond(service.update(id = id, request = request, held = call.heldCapabilities()))
            }

            /**
             * Deletes an export definition.
             *
             * Description: Removes the definition and, through the table's cascade, its columns. Definitions are shared rather than owned, so this is a management action and not something a reader may do to somebody else's saved extract.
             *
             * Tag: custom-exports
             * Path: id Export definition identifier.
             * Response: 204 Deleted.
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_MANAGE.
             * Response: 404 No definition with that id.
             */
            delete("/{id}") {
                service.delete(definitionId(call.parameters["id"]))
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Runs a saved definition and returns the CSV.
             *
             * Description: Applies every constraint the definition declared, plus the shop narrowing, which is not the operator's to switch off. Refuses rather than degrades: a definition whose subject or field key the catalogue no longer resolves answers 409, and one naming a column this caller may not see answers 403 — a CSV quietly one column short, or one column of blanks, is worse than no CSV at all.
             *
             * The file is **UTF-8 with a BOM**, deliberately unlike the ISO-8859-1 DATEV exports: this is an extract an operator assembled for their own spreadsheet, not a file DATEV's importer reads, and the BOM is what stops Excel decoding it as the system code page.
             *
             * Requires only `CUSTOM_EXPORT_READ`, not the manage tier. That is safe because the run re-checks every field against the **runner's** own capabilities and refuses outright — so a reader cannot reach data through a definition somebody better-privileged saved. Definitions are shared and outlive whoever built them, which is precisely why the check cannot live at save time alone.
             *
             * A body cannot carry what an operator needs to know *about* the file, so the row count and the truncation flag ride on `X-Export-Row-Count` and `X-Export-Truncated`. Truncation is not an error — a partial file is still useful — but an operator who is not told is one who reconciles against a silently short list.
             *
             * Tag: custom-exports
             * Path: id Export definition identifier.
             * Response: 200 The CSV file, `text/csv; charset=UTF-8`.
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks CUSTOM_EXPORT_READ, or the definition names a field this caller may not see.
             * Response: 404 No definition with that id.
             * Response: 409 The definition is broken — the catalogue no longer resolves its subject, a field or a toggle.
             */
            get("/{id}/run") {
                val result = service.run(definitionId(call.parameters["id"]), call.heldCapabilities())

                call.response.header("X-Export-Row-Count", result.rowCount.toString())
                call.response.header("X-Export-Truncated", result.truncated.toString())
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            // Named by the service, never re-derived here. The bytes and the name the
                            // operator files them under are one decision, and a second copy of the
                            // naming rule — including its "must not begin EXTF" guard — is how a file
                            // comes to be called something the format is not.
                            result.fileName,
                        ).toString(),
                )
                call.respondBytes(
                    bytes = result.bytes,
                    contentType = ContentType("text", "csv").withCharset(Charsets.UTF_8),
                    status = HttpStatusCode.OK,
                )
            }
        }
    }
}

/**
 * The `{id}` path parameter as a number.
 *
 * Shared by the two routes that take one so they cannot answer differently for the same bad input:
 * a non-numeric id is a malformed request, not a missing definition, and must not be reported as a
 * 404 that reads to the caller as "it was deleted".
 */
private fun definitionId(raw: String?): Long =
    raw?.toLongOrNull()
        ?: throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid id",
        )
