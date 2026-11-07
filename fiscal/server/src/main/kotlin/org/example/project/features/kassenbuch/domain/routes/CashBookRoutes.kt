/**
 * REST API routes for cash-book-to-cash-book transfers (Geldtransit) and the Kassensturz close of
 * a non-fiscal cash book.
 *
 * Defines HTTP endpoints for creating an atomic two-leg transfer, reversing one as a whole, and
 * previewing/recording/listing a book's Kassensturz closes. Services throw [PosException]; the
 * global StatusPages plugin maps them to RFC 7807 application/problem+json responses.
 */
package org.example.project.features.kassenbuch.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.kassenbuch.domain.service.CashBookService
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("CashBookRoutes")

fun Route.cashBookTransferRoutes() {
    val transferService by inject<CashBookTransferService>()
    val closeService by inject<CashBookCloseService>()
    val bookService by inject<CashBookService>()

    authenticate("auth-jwt") {
        route("/api/v1/kassenbuch/transfers") {
            /**
             * Create a cash transfer between two books.
             *
             * Description: Requires the capability RoutePermissions names for this route. Books both legs of a Geldtransit atomically — one withdrawal and one deposit sharing a transfer id — so the two books can never disagree.
             *
             * Tag: cash-books
             * Response: 400 The request body failed validation, amount is not a positive decimal, the books belong to different shops, a book is deactivated, or the fiscal leg has no device to sign it.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 The source or target cash book, or the calling employee, does not exist.
             * Response: 409 KASSENBUCH_INSUFFICIENT_CASH — the source book holds less cash than the amount (Kassenminus guard; for the fiscal Ladenkasse the not-yet-closed cash takings count as held). Neither leg is booked.
             */
            post {
                val request = call.receive<CashBookDto.CreateTransferRequestDto>()

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

                val response = transferService.createTransfer(tokenClaims.employeeId, tokenClaims.deviceId, request)
                logger.info("Created transfer ${response.transferId}: ${response.fromBelegNr} -> ${response.toBelegNr}")
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Reverse a cash transfer.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. Reverses both legs as a Storno pair; the original entries stay in the ledger. A reason is required.
             *
             * Tag: cash-books
             * Path: transferId Transfer identifier.
             * Response: 400 transferId is missing, or the reason failed validation.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No transfer with that id.
             * Response: 422 The transfer is already reversed. A leg already swept into a closed Z-Bon is still reversible.
             */
            post("/{transferId}/reverse") {
                val transferId =
                    call.parameters["transferId"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing transferId",
                        )
                val request = call.receive<CashBookDto.ReverseTransferRequestDto>()

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

                val response =
                    transferService.reverseTransfer(
                        tokenClaims.employeeId,
                        tokenClaims.deviceId,
                        transferId,
                        request.reason,
                    )
                logger.info("Reversed transfer $transferId as ${response.transferId}")
                call.respond(HttpStatusCode.Created, response)
            }
        }

        route("/api/v1/kassenbuch/books") {
            /**
             * List the shop's cash books.
             *
             * Description: Requires the capability RoutePermissions names for this route. Each book comes back with its own current balance. Balances are never summed across books — the fiscal Ladenkasse and a petty-cash book are separate ledgers. Deactivated books are omitted unless `includeInactive=true`, which only the book-management dialog sends.
             *
             * Tag: cash-books
             * Query: includeInactive Optional boolean; also return deactivated books. Defaults to false.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, bookService.list(includeInactive))
            }

            /**
             * Create a cash book.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. The new book is always non-fiscal — the request shape has no fiscal flag, so the shop's single fiscal Ladenkasse can never be duplicated.
             *
             * Tag: cash-books
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 409 A cash book with that name already exists — active or deactivated, compared trimmed and case-insensitively.
             */
            post {
                val request = call.receive<CashBookDto.CreateRequestDto>()

                val validationErrors = request.validate()
                if (validationErrors.isNotEmpty()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        validationErrors.first(),
                    )
                }

                val response = bookService.create(request)
                logger.info("Created cash book ${response.id}: ${response.name}")
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Update a cash book.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. Name, DATEV account, cost centre, close interval, active flag and sort order may change. The fiscal flag cannot — the request shape has no field for it, so a book can never be turned into the Ladenkasse.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             * Response: 409 Another cash book (active or deactivated) already carries that name, compared trimmed and case-insensitively.
             */
            put("/{bookId}") {
                val bookId = call.bookIdOrThrow()
                val request = call.receive<CashBookDto.UpdateRequestDto>()

                val validationErrors = request.validate()
                if (validationErrors.isNotEmpty()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        validationErrors.first(),
                    )
                }

                val response = bookService.update(bookId, request)
                logger.info("Updated cash book ${response.id}: ${response.name}")
                call.respond(HttpStatusCode.OK, response)
            }
        }

        route("/api/v1/kassenbuch/books/{bookId}") {
            /**
             * Preview a cash book's next Kassensturz.
             *
             * Description: Requires the capability RoutePermissions names for this route. Read-only — reports the period that would be closed and the book balance to count against, without writing anything.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             * Response: 422 The book is the fiscal Ladenkasse, has no entries and was never closed, or has nothing new to close since its last close.
             */
            get("/close/preview") {
                val bookId = call.bookIdOrThrow()
                call.respond(HttpStatusCode.OK, closeService.preview(bookId))
            }

            /**
             * Record a cash book Kassensturz.
             *
             * Description: Requires MANAGER role, checked both centrally and inline here. Books the difference between the counted cash and the book balance, then locks the period so nothing can be back-dated into it. `countedBalance` is a non-negative decimal string.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number, the body failed validation, or countedBalance is not a non-negative decimal.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id, or the calling employee does not exist.
             * Response: 422 The book is the fiscal Ladenkasse, has no entries and was never closed, or has nothing new to close since its last close.
             */
            post("/close") {
                val bookId = call.bookIdOrThrow()
                val request = call.receive<CashBookDto.CloseRequestDto>()

                val validationErrors = request.validate()
                if (validationErrors.isNotEmpty()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        validationErrors.first(),
                    )
                }

                val countedBalance =
                    request.countedBalance.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "countedBalance must be a non-negative decimal string",
                        )

                val tokenClaims =
                    call.getTokenClaims()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val response = closeService.close(tokenClaims.employeeId, bookId, countedBalance, request.notes)
                logger.info("Closed cash book $bookId period ${response.periodStart}..${response.periodEnd}")
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * List a cash book's Kassensturz history.
             *
             * Description: Requires the capability RoutePermissions names for this route. Read-only, most recent period first.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             */
            get("/closes") {
                val bookId = call.bookIdOrThrow()
                call.respond(HttpStatusCode.OK, closeService.closeHistory(bookId))
            }

            /**
             * List the tills that may sign the Ladenkasse's closing movements.
             *
             * Description: Requires the capability RoutePermissions names for this route (TERMINAL_MANAGE). Only active, fiscal, TSE-registered CASHIER tills of the book's shop qualify. Empty for a non-fiscal book.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             */
            get("/signing-terminal/candidates") {
                call.respond(HttpStatusCode.OK, bookService.signingTerminalCandidates(call.bookIdOrThrow()))
            }

            /**
             * Designate the Ladenkasse's signing till.
             *
             * Description: Requires the capability RoutePermissions names for this route (TERMINAL_MANAGE). The designated till's TSE client signs every cash movement of a closing (Kassendifferenz, tip payouts, top-up, daily Geldtransit) whichever device triggers the closing, so the admin panel can close the day. `terminalId: null` clears it; the calling till then signs, as before. Audited as SET_CASH_BOOK_SIGNING_TERMINAL.
             *
             * Tag: cash-books
             * Path: bookId Cash book identifier.
             * Response: 400 bookId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No cash book with that id.
             * Response: 409 CASH_BOOK_SIGNER_INELIGIBLE — the book is not the fiscal Ladenkasse, or the terminal does not exist, belongs to another shop, is not a CASHIER till, or is inactive, non-fiscal or not TSE-registered.
             */
            put("/signing-terminal") {
                val bookId = call.bookIdOrThrow()
                val request = call.receive<CashBookDto.SigningTerminalRequestDto>()
                val claims = call.getTokenClaims() ?: return@put call.respond(HttpStatusCode.Unauthorized)
                call.respond(
                    HttpStatusCode.OK,
                    bookService.setSigningTerminal(bookId, request.terminalId, claims.employeeId, claims.deviceId),
                )
            }
        }
    }
}

private fun ApplicationCall.bookIdOrThrow(): Long =
    parameters["bookId"]?.toLongOrNull()
        ?: throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid bookId",
        )
