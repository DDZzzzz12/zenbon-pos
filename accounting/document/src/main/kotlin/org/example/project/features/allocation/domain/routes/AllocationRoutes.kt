/**
 * Allocation API Routes — Zuordnung: assigning payments to what they paid for.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET    /api/allocations/queue          - Bank transactions still carrying a residual
 * - GET    /api/allocations/open-invoices  - Candidate targets: invoices that still owe something
 * - GET    /api/allocations/open-instalments - Candidate targets: contract instalments still expecting money
 * - GET    /api/allocations/telemetry      - How allocations came about, in counts
 * - POST   /api/allocations                - Assign part or all of a payment
 * - POST   /api/allocations/batch          - Assign one payment across several targets, all or none
 * - DELETE /api/allocations/{id}           - Reverse an assignment
 */
package org.example.project.features.allocation.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.allocation.domain.service.AllocationService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.allocationRoutes() {
    val allocationService by inject<AllocationService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/allocations") {
            /**
             * The assignment queue.
             *
             * Description: Requires MANAGER role. Lists bank transactions on statements already assigned to one of our own accounts, newest value date first. NICHT_ZUGEORDNET returns only those still carrying an unassigned residual; ALLE also returns settled ones, which is how an assignment is found again to reverse it. Transactions on a statement not yet assigned to an account are never listed - that is a Bank work item.
             *
             * Tag: allocations
             * Query: filter NICHT_ZUGEORDNET or ALLE. Defaults to NICHT_ZUGEORDNET.
             * Query: limit Page size, 1 to 500. Defaults to 200.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 One page of the queue plus the total.
             * Response: 400 filter is not one of the two names.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/queue") {
                val filter = call.filterParam()
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, allocationService.queue(filter, limit, offset))
            }

            /**
             * Candidate targets for an assignment.
             *
             * Description: Requires MANAGER role. Invoices that still owe something and are not marked paid, earliest due date first with undated ones last. Narrow to one creditor with supplierId when the queue row already identifies the supplier.
             *
             * Tag: allocations
             * Query: supplierId Only this creditor's invoices.
             * Query: limit Page size, 1 to 500. Defaults to 200.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 One page of open invoices plus the total.
             * Response: 400 supplierId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/open-invoices") {
                val supplierId =
                    call.request.queryParameters["supplierId"]?.let { raw ->
                        raw.toLongOrNull() ?: throw invalidRequest("supplierId must be a number")
                    }
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, allocationService.openInvoices(supplierId, limit, offset))
            }

            /**
             * Candidate targets that are case-C (STANDALONE) invoices.
             *
             * Description: Requires MANAGER role. Outgoing invoices that still expect money — the AR panel of the Zuordnung editor. Issued, not cancelled, with a positive effective residual; `debtorId` narrows to one debtor. The lifecycle is the gate: an outgoing invoice has no paid_at, status is what makes it a live obligation.
             *
             * Tag: allocations
             * Query: debtorId Narrow to one debtor.
             * Query: limit Page size, 1 to 500. Defaults to 200.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 One page of open sales invoices plus the total.
             * Response: 400 debtorId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/open-sales-invoices") {
                val debtorId =
                    call.request.queryParameters["debtorId"]?.let { raw ->
                        raw.toLongOrNull() ?: throw invalidRequest("debtorId must be a number")
                    }
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, allocationService.openSalesInvoices(debtorId, limit, offset))
            }

            /**
             * Candidate targets that are contract instalments.
             *
             * Description: Requires MANAGER role. Contract instalments that still expect money, across every contract, earliest due date first. An instalment whose expectation nobody has stated is omitted - an unstated figure is not something to offer as a target - and one written off as WAIVED is never listed. The status is derived from the residual and the due date, so it agrees with the Vertraege screen.
             *
             * Tag: allocations
             * Query: limit Page size, 1 to 500. Defaults to 200.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 One page of open instalments plus the total.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/open-instalments") {
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, allocationService.openInstalments(limit, offset))
            }

            // Declared before the `/{id}`-shaped route below: Ktor resolves in declaration order,
            // and a literal segment registered after a parameter one can be swallowed by it.
            /**
             * How allocations came about, in counts.
             *
             * Description: Requires MANAGER role. Counts every allocation ever made by how it came about, and the accepted suggestions again by which signal of the ladder produced them. Every matchedBy constant is present with a zero rather than absent, so a caller can render a number without having to tell "none yet" from "unknown". This is evidence for judging which signals earn their place - there is no screen and no chart.
             *
             * Tag: allocations
             * Response: 200 The counts by matchedBy, the counts by signal, and the total.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/telemetry") {
                call.respond(HttpStatusCode.OK, allocationService.telemetry())
            }

            /**
             * Assign part or all of a payment.
             *
             * Description: Requires MANAGER role. Writes the allocation and both residuals in one transaction and returns the new residuals, so a split can be entered line by line without refetching. Assigning more than the payment has left is refused - that is an operator error, not a state worth recording. INVOICE and CONTRACT_INSTALMENT name a target that carries its own residual and each needs its id. The other four settle the payment outright: CASH_TRANSIT needs a counterKassenbuchHeaderId naming the cash-book side and INTERNAL_TRANSFER a counterBankAccountId naming the other account, EXPENSE_NO_DOC needs a bookingText because with no document it is the only record of what was paid, and FEE needs neither. Whether a named Geldtransit entry actually matches the payment in direction, amount or date is not checked - the operator judges that.
             *
             * Tag: allocations
             * Response: 201 The written allocation and both new residuals.
             * Response: 400 Unknown kind, non-decimal or non-positive amount, a missing id or booking text for the declared kind, or a matchedBy a client may not claim.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No transaction, invoice, instalment, cash-book entry or bank account with that id.
             * Response: 409 The amount exceeds what is unassigned, or the statement has no bank account yet.
             */
            post {
                val request = call.receive<AllocationDto.CreateRequestDto>()
                val createdBy = call.getEmployeeIdFromJwt()
                call.respond(HttpStatusCode.Created, allocationService.create(request, createdBy))
            }

            // Declared before the `/{id}`-shaped route below: Ktor resolves in declaration order, and
            // a literal segment registered after a parameter one can be swallowed by it.
            /**
             * Assign one payment across several targets at once.
             *
             * Description: Requires MANAGER role. Writes the whole list in one transaction - all or none. This is how an accepted suggestion naming several invoices is booked: separate calls could half-succeed, leaving a payment partly assigned against a set that no longer sums. Every item is validated before any is written, and the items sharing a payment are measured against that payment's residual together, so two halves that each fit but do not fit jointly are refused before anything is stored.
             *
             * Tag: allocations
             * Response: 201 The written allocations, in request order, each with both new residuals.
             * Response: 400 Empty list, unknown kind, non-decimal or non-positive amount, missing id for the declared kind, or a matchedBy a client may not claim.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No transaction, invoice or instalment with one of those ids.
             * Response: 409 The items exceed what is unassigned, or the statement has no bank account yet. Nothing is written.
             */
            post("/batch") {
                val requests = call.receive<List<AllocationDto.CreateRequestDto>>()
                val createdBy = call.getEmployeeIdFromJwt()
                call.respond(HttpStatusCode.Created, allocationService.createAll(requests, createdBy))
            }

            /**
             * Reverse an assignment.
             *
             * Description: Requires MANAGER role. Removes the allocation and restores both residuals, returning the payment to the queue and the invoice to the open list. The allocation row is removed rather than flagged - it recorded our decision, not an asserted fact - and the reversal is recorded as a REVERSE_ALLOCATION row in the audit log, naming who reversed which assignment and its amount.
             *
             * Tag: allocations
             * Path: id Allocation identifier.
             * Response: 204 Reversed.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No allocation with that id.
             */
            delete("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw invalidRequest("Invalid allocation ID")
                allocationService.reverse(id, call.getEmployeeIdFromJwt())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** The `filter` query parameter, defaulting to the queue an operator opens the screen for. */
private fun ApplicationCall.filterParam(): AllocationDto.QueueFilter {
    val raw = request.queryParameters["filter"] ?: return AllocationDto.QueueFilter.NICHT_ZUGEORDNET
    return AllocationDto.QueueFilter.entries.firstOrNull { it.name == raw }
        ?: throw invalidRequest("Unknown filter '$raw'")
}

private fun invalidRequest(message: String): PosException = PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, message)
