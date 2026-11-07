/**
 * Bank Account API Routes — our own accounts, and the manual assignment of a statement to one.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks — the second path prefix gets its own route block
 *   inside the same wrapper
 *
 * Endpoints:
 * - GET  /api/bank-accounts               - List, with continuity breaks and unassigned statements
 * - GET  /api/bank-accounts/{id}          - One account
 * - GET  /api/bank-accounts/{id}/statements - The statements that account holds, oldest first
 * - GET  /api/bank-accounts/{id}/statements/{statementId}/transactions - One statement's bookings, paged
 * - POST /api/bank-accounts               - Create an account
 * - POST /api/bank-accounts/resolve       - Re-run statement→account resolution
 * - PUT  /api/bank-statements/{id}/account - Assign a statement to an account by hand
 */
package org.example.project.features.bank.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bank.domain.service.BankAccountService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * How many bookings one page of a statement carries, and the ceiling a caller may ask for.
 *
 * The bookings render inside an expanded table row, not on a screen of their own, so a whole CAMT
 * month arriving at once would bury the statement list it is nested in.
 */
private const val STATEMENT_TRANSACTION_PAGE_SIZE = 100

fun Route.bankAccountRoutes() {
    val bankAccountService by inject<BankAccountService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/bank-accounts") {
            /**
             * List our own bank accounts.
             *
             * Description: Active accounts ordered by bank name, each with how many statements it holds and the ids of statements whose opening balance does not continue the previous closing balance. Also returns the statements no account of ours claims, which is the work item an operator resolves.
             *
             * Tag: bank-accounts
             * Response: 200 The account list plus unassigned statements.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                call.respond(HttpStatusCode.OK, bankAccountService.list())
            }

            /**
             * Fetch one bank account.
             *
             * Description: Returns the account with its statement count and continuity breaks.
             *
             * Tag: bank-accounts
             * Path: id Bank account identifier.
             * Response: 200 The account.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No bank account with that id.
             */
            get("/{id}") {
                call.respond(HttpStatusCode.OK, bankAccountService.get(call.longParam("id")))
            }

            /**
             * List the statements one account holds.
             *
             * Description: Requires MANAGER role. Oldest first, in the period order the continuity check runs in. Each statement carries its own continuityBreak flag, meaning its opening balance does not continue the previous statement's closing balance and one is missing between them — the same finding the list endpoint reports as continuityBreaks, but placed on the statement it concerns rather than returned as bare ids. An account holding no statements returns an empty list, not a 404.
             *
             * Tag: bank-accounts
             * Path: id Bank account identifier.
             * Response: 200 The account's statements.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No bank account with that id.
             */
            get("/{id}/statements") {
                call.respond(HttpStatusCode.OK, bankAccountService.statementsFor(call.longParam("id")))
            }

            /**
             * List one statement's bookings.
             *
             * Description: Requires MANAGER role. Oldest first by value date, which is the order a bank statement is read down. The statement must belong to the account named in the path — a statement that exists on a different account answers 404, the same as one that does not exist, rather than confirming the id is real. Each booking carries its effective residual, so a caller can tell what is still waiting to be assigned in Zuordnung without asking a second endpoint. Paged, because a monthly CAMT file can carry several hundred bookings; total is the statement's whole count, not the page's.
             *
             * Tag: bank-accounts
             * Path: id Bank account identifier.
             * Path: statementId Statement identifier, which must belong to that account.
             * Query: limit Page size, 1 to 100. Defaults to 100.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 One page of the statement's bookings.
             * Response: 400 id or statementId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such bank account, or no such statement on it.
             */
            get("/{id}/statements/{statementId}/transactions") {
                call.respond(
                    HttpStatusCode.OK,
                    bankAccountService.statementTransactions(
                        bankAccountId = call.longParam("id"),
                        statementId = call.longParam("statementId"),
                        limit =
                            call.request.queryParameters["limit"]
                                ?.toIntOrNull()
                                ?.coerceIn(1, STATEMENT_TRANSACTION_PAGE_SIZE)
                                ?: STATEMENT_TRANSACTION_PAGE_SIZE,
                        offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L,
                    ),
                )
            }

            /**
             * Create a bank account.
             *
             * Description: Requires MANAGER role. The IBAN is stored canonically, so the spacing a bank prints does not create a second account.
             *
             * Tag: bank-accounts
             * Response: 201 The created account.
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 BANK_ACCOUNT_IBAN_TAKEN — another account already carries that IBAN.
             */
            post {
                val dto = bankAccountService.create(call.receive<BankAccountDto.CreateRequestDto>())
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Re-run statement to account resolution.
             *
             * Description: Requires MANAGER role. Matches the bank-asserted IBAN of every unassigned statement against our accounts. Run it after adding an account, so statements that arrived before it are claimed. A statement already carrying an account is left untouched.
             *
             * Tag: bank-accounts
             * Response: 200 How many statements the sweep resolved.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post("/resolve") {
                call.respond(
                    HttpStatusCode.OK,
                    BankAccountDto.ResolveResponseDto(bankAccountService.resolveUnassigned()),
                )
            }
        }

        // Same authenticate wrapper, different path prefix — never a second authenticate block.
        route("/api/bank-statements") {
            /**
             * Assign a statement to one of our accounts by hand.
             *
             * Description: Requires MANAGER role. For a statement whose IBAN automatic resolution could not match. A manual assignment survives later resolution sweeps, which only touch unassigned statements.
             *
             * Tag: bank-accounts
             * Path: id Bank statement identifier.
             * Response: 200 The account the statement now belongs to.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No statement with that id, or no account with that id.
             */
            put("/{id}/account") {
                val dto =
                    bankAccountService.assignStatement(
                        call.longParam("id"),
                        call.receive<BankAccountDto.AssignStatementRequestDto>(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid $name")
