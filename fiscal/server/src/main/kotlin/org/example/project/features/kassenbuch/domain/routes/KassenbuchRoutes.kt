/**
 * REST API routes for Kassenbuch (cash book) operations.
 *
 * Defines HTTP endpoints for DATEV-compliant cash book operations.
 * Services throw [PosException]; the global StatusPages plugin maps them
 * to RFC 7807 application/problem+json responses.
 */
package org.example.project.features.kassenbuch.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.InsufficientPermissionsException
import org.example.project.utils.describeDeferredApprovalResponses
import org.example.project.utils.requireIsoDate
import org.example.project.utils.requirePermissionOrDefer
import org.example.project.utils.toIdListOrNull
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("KassenbuchRoutes")

fun Route.kassenbuchRoutes() {
    val service by inject<KassenbuchService>()

    authenticate("auth-jwt") {
        route("/api/v1/kassenbuch") {
            /**
             * Create cash book entries.
             *
             * Description: Requires the capability RoutePermissions names for this route. One call books a whole receipt — a multi-VAT receipt becomes several entries sharing one Belegnummer, so the DATEV export keeps them together. The acting employee and device come from the JWT.
             *
             * Tag: kassenbuch
             * Response: 202 The Beleg date is past the shop's limit and the caller lacks CASHBOOK_BACKDATE — an approval request was raised for a manager, and the entry is booked once approved.
             * Response: 400 The request body failed validation; or the Beleg date is in the future, more than a year back, or past the shop's limit (setting kassenbuch-beleg-age, default 31 days) without a lateBookingReason. A Beleg date inside an already closed period is accepted — the entry books into the open period by its booking time and keeps its date. A date past the limit is accepted with a lateBookingReason and CASHBOOK_BACKDATE (hybrid authorization); the reason is stored on the entry.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 The named cash book, account or VAT class does not exist.
             * Response: 409 KASSENBUCH_INSUFFICIENT_CASH — the entry is a withdrawal larger than the book holds (Kassenminus guard). For the fiscal Ladenkasse "holds" includes the cash takings of bills no Z-Bon has closed yet; see GET /available-cash. Nothing is booked.
             */
            post {
                val request = call.receive<KassenbuchDto.CreateKassenbuchEntryRequestDto>()

                val validationErrors = request.validate()
                if (validationErrors.isNotEmpty()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        validationErrors.first(),
                    )
                }

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)

                if (!service.needsLateBookingOverride(request)) {
                    val response = service.createEntry(tokenClaims.employeeId, tokenClaims.deviceId, request)
                    logger.info("Created ${response.entries.size} kassenbuch entries with belegNr: ${response.belegNr}")
                    return@post call.respond(HttpStatusCode.Created, response)
                }

                // Beleg date past the shop's limit: a documented late booking. The reason is
                // mandatory; the authority is CASHBOOK_BACKDATE — held, lent by a step-up, or granted
                // by a manager's approval (202 until then).
                if (request.lateBookingReason.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Beleg date ${request.belegDatum} is older than the shop's limit; a late booking needs a reason",
                    )
                }
                try {
                    val authResult =
                        call.requirePermissionOrDefer(
                            requiredCapability = Capability.CASHBOOK_BACKDATE,
                            operation = "BACKDATE_CASH_BOOK_ENTRY",
                            details =
                                mapOf(
                                    "belegDatum" to request.belegDatum.orEmpty(),
                                    "belegtext" to request.belegtext,
                                    "amount" to request.lines.joinToString(" + ") { it.amount },
                                    "reason" to request.lateBookingReason.orEmpty(),
                                ),
                        ) {
                            // Executed when a manager approves — booked as the requesting employee.
                            val approved = service.createBackdatedEntry(tokenClaims.employeeId, tokenClaims.deviceId, request)
                            "Kassenbuch entry ${approved.belegNr} booked with Beleg date ${request.belegDatum}"
                        } ?: return@post

                    val response = service.createBackdatedEntry(authResult.employeeId, tokenClaims.deviceId, request)
                    logger.info(
                        "Created backdated kassenbuch entry ${response.belegNr} (Beleg date ${request.belegDatum}) " +
                            "via ${authResult.authorizationType}",
                    )
                    call.respond(HttpStatusCode.Created, response)
                } catch (e: InsufficientPermissionsException) {
                    // Already responded in requirePermissionOrDefer
                }
            }.describeDeferredApprovalResponses()

            /**
             * Get the current cash book balance.
             *
             * Description: Requires the capability RoutePermissions names for this route. Defaults to the shop's fiscal book and never sums across books — a cross-book total would be meaningless. Pass `cashBookId` to ask about another book. The body is a canonical decimal string (`1234.56`); the legacy `raw=true` parameter is accepted and ignored.
             *
             * Tag: kassenbuch
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             */
            get("/balance") {
                val cashBookId = call.request.queryParameters["cashBookId"]?.toLongOrNull()
                // A canonical decimal string ("1234.56"), like every other money field. `?raw=true`
                // selected that form while the default was the German display text ("1.234,56",
                // admin16 NOTE-5); it is still accepted, and now changes nothing.
                call.respond(HttpStatusCode.OK, service.getCurrentBalance(cashBookId))
            }

            /**
             * What the fiscal Ladenkasse should hold right now.
             *
             * Description: Requires the capability RoutePermissions names for this route. Returns the book balance, the cash of bills not yet closed (which the book shows only after the Z-Bon books its aggregate) and their sum — the figure a drawer count is compared against. Clients prefill the count field with `expectedCash`.
             *
             * Tag: kassenbuch
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/cash-count/expected") {
                call.respond(HttpStatusCode.OK, service.getCashCountExpectation())
            }

            /**
             * What a cash book can pay out right now.
             *
             * Description: Requires the capability RoutePermissions names for this route. The Kassenminus guard refuses any withdrawal (expense, Entnahme, tip payout, outgoing Geldtransit) above `availableCash` with 409 KASSENBUCH_INSUFFICIENT_CASH. For a non-fiscal book it is the book balance; for the fiscal Ladenkasse (the default when `cashBookId` is omitted) it is the book balance plus the cash takings of bills no Z-Bon has closed yet — the same figure a drawer count is compared against.
             *
             * Tag: kassenbuch
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book of this shop with that id.
             */
            get("/available-cash") {
                val cashBookId = call.request.queryParameters["cashBookId"]?.toLongOrNull()
                call.respond(HttpStatusCode.OK, service.getAvailableCash(cashBookId))
            }

            /**
             * Record a till drawer count against the fiscal Ladenkasse.
             *
             * Description: Requires the capability RoutePermissions names for this route. Called before the Z-Bon so the closing sweep picks the resulting difference entry up into the closing that caused it. The counted amount is an exact decimal sent as a string, and the server books the difference against the expected drawer content — book balance plus the cash of bills not yet closed (see GET /cash-count/expected).
             *
             * Tag: kassenbuch
             * Response: 400 The body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             */
            post("/cash-count") {
                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val request = call.receive<KassenbuchDto.CashCountRequestDto>()
                val dto =
                    service.recordCashCount(
                        tokenClaims.employeeId,
                        tokenClaims.deviceId,
                        request,
                    )
                logger.info(
                    "Recorded cash count: counted=${dto.countedAmount} expected=${dto.expectedCash} " +
                        "(book=${dto.bookBalance} + unbooked=${dto.unbookedCashTakings}) " +
                        "difference=${dto.difference} entryId=${dto.entryId ?: "none"}",
                )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List the most recent cash book entries.
             *
             * Description: Requires the capability RoutePermissions names for this route. `limit` is clamped to 1–100 (default 5) and `cashBookId` selects a book other than the fiscal one.
             *
             * Tag: kassenbuch
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/entries/recent") {
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 100) ?: 5
                val cashBookId = call.request.queryParameters["cashBookId"]?.toLongOrNull()
                logger.info("Getting {} most recent kassenbuch entries", limit)
                call.respond(HttpStatusCode.OK, service.getRecentEntries(limit, cashBookId))
            }

            /**
             * Search cash book entries.
             *
             * Description: Requires the capability RoutePermissions names for this route. Free-text search across all periods, for when the Belegnummer is known but the month is not. The term comes from the required `q` query parameter; `cashBookIds` takes a comma-separated list to narrow the books searched.
             *
             * Tag: kassenbuch
             * Response: 400 The q query parameter is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/entries/search") {
                val query =
                    call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing or blank q query parameter",
                        )
                val cashBookIds = call.request.queryParameters["cashBookIds"].toIdListOrNull()

                call.respond(HttpStatusCode.OK, service.searchEntries(query, cashBookIds))
            }

            /**
             * List cash book entries for a period.
             *
             * Description: Requires the capability RoutePermissions names for this route. Both `startDate` and `endDate` query parameters are required. `unlinkedOnly=true` narrows the result to entries with no document attached — the working list for chasing missing receipts — and `cashBookId` selects a book.
             *
             * Tag: kassenbuch
             * Response: 400 startDate or endDate is missing, or VALIDATION_FAILED — one of them is not a `yyyy-MM-dd` date.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/entries") {
                val startDate =
                    call.request.queryParameters.requireIsoDate("startDate")
                val endDate =
                    call.request.queryParameters.requireIsoDate("endDate")
                val unlinkedOnly = call.request.queryParameters["unlinkedOnly"]?.toBooleanStrictOrNull() ?: false
                val cashBookId = call.request.queryParameters["cashBookId"]?.toLongOrNull()

                call.respond(HttpStatusCode.OK, service.getEntryHistory(startDate, endDate, unlinkedOnly, cashBookId))
            }

            /**
             * Get a cash book entry by ID.
             *
             * Tag: kassenbuch
             * Path: entryId Cash book entry identifier.
             * Response: 400 entryId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry with that id.
             */
            get("/entries/{entryId}") {
                val entryId =
                    call.parameters["entryId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid entry ID",
                        )

                call.respond(HttpStatusCode.OK, service.getEntryById(entryId))
            }

            /**
             * Get a grouped cash book entry.
             *
             * Description: Requires the capability RoutePermissions names for this route. Returns the header with all its line items — the multi-VAT receipt reassembled as the operator entered it, which is what the detail dialog shows.
             *
             * Tag: kassenbuch
             * Path: headerId Cash book entry header identifier.
             * Response: 400 headerId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry header with that id.
             */
            get("/entries/grouped/{headerId}") {
                val headerId =
                    call.parameters["headerId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid header ID",
                        )

                call.respond(HttpStatusCode.OK, service.getGroupedEntryDetail(headerId))
            }

            /**
             * Attach a document to a cash book entry.
             *
             * Description: Requires the capability RoutePermissions names for this route. Links a stored PDF — the scanned receipt — to the entry header, which is what clears it from the unlinked working list.
             *
             * Tag: kassenbuch
             * Path: headerId Cash book entry header identifier.
             * Response: 400 headerId is not a number.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry header with that id, or no document with that id.
             * Response: 409 The entry already has a document linked.
             */
            put("/entries/{headerId}/link-document") {
                val headerId =
                    call.parameters["headerId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid header ID",
                        )
                val request = call.receive<KassenbuchDto.LinkDocumentRequestDto>()

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val dto = service.linkDocument(tokenClaims.employeeId, tokenClaims.deviceId, headerId, request.documentId)
                logger.info("Linked document ${request.documentId} to kassenbuch header $headerId")
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Detach the document from a cash book entry.
             *
             * Description: Requires the capability RoutePermissions names for this route. Unlinks the PDF; the document itself stays in the archive.
             *
             * Tag: kassenbuch
             * Path: headerId Cash book entry header identifier.
             * Response: 400 headerId is not a number.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry header with that id, or it has no linked document.
             */
            delete("/entries/{headerId}/link-document") {
                val headerId =
                    call.parameters["headerId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid header ID",
                        )

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val dto = service.unlinkDocument(tokenClaims.employeeId, tokenClaims.deviceId, headerId)
                logger.info("Unlinked document from kassenbuch header $headerId")
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Replace the document on a cash book entry.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. A soft replace — the previous link is superseded rather than erased, so the audit trail still shows which document was attached when.
             *
             * Tag: kassenbuch
             * Path: headerId Cash book entry header identifier.
             * Response: 400 headerId is not a number.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry header with that id, or no document with that id.
             */
            put("/entries/{headerId}/replace-document") {
                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val headerId =
                    call.parameters["headerId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid header ID",
                        )
                val request = call.receive<KassenbuchDto.ReplaceDocumentRequestDto>()

                val dto =
                    service.replaceDocument(
                        tokenClaims.employeeId,
                        tokenClaims.deviceId,
                        headerId,
                        request.newDocumentId,
                    )
                logger.info(
                    "Replaced document on kassenbuch header $headerId with document ${request.newDocumentId}",
                )
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Correct a cash book entry.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. Nothing is overwritten — the original is reversed by a Storno and the corrected values are booked as a new entry, so the ledger keeps both, as §146 AO requires. A reason is part of the request.
             *
             * Tag: kassenbuch
             * Path: headerId Cash book entry header identifier.
             * Response: 400 headerId is not a number, or the correction failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No entry header with that id.
             * Response: 409 The entry is already reversed, or its period is closed; or KASSENBUCH_INSUFFICIENT_CASH — the corrected re-entry is a withdrawal the book cannot cover even after the Storno (checked before anything is booked). The Storno itself is never refused for a Kassenminus.
             */
            post("/entries/{headerId}/correct") {
                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val headerId =
                    call.parameters["headerId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid header ID",
                        )
                val request = call.receive<KassenbuchDto.CorrectEntryRequestDto>()

                val dto =
                    service.createCorrection(
                        tokenClaims.employeeId,
                        tokenClaims.deviceId,
                        headerId,
                        request,
                    )
                logger.info("Corrected kassenbuch header $headerId (reason='${request.reason.take(40)}')")
                call.respond(HttpStatusCode.Created, dto)
            }
        }
    }
}
