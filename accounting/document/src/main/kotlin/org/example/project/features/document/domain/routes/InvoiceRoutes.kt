/**
 * Invoice API Routes — the normalized `invoice` / `invoice_booking` aggregate.
 *
 * A separate file and a separate top-level route group from `DocumentRoutes.kt`, because writes are
 * keyed on **invoice id, not document id**: `source_document_id` is deliberately non-unique, since
 * one scanned PDF may hold several invoices, so a document id cannot address an invoice
 * unambiguously. Reads and creation are document-keyed and live in `DocumentRoutes.kt`.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * This file exists rather than a second `route("/api/invoices")` inside `DocumentRoutes.kt` for
 * exactly that reason: `DocumentRoutes.kt` already owns one `authenticate` block for
 * `/api/documents`, and every other entry in `Routing.kt` is one route group per file.
 *
 * Endpoints:
 * - GET /api/invoices - The Rechnungen list, one bucket at a time
 * - GET /api/invoices/{id} - One invoice by its own id, the only way to reach a document-less one
 * - PUT /api/invoices/{id} - Replace the supplier-asserted header (409 when EINVOICE_XML)
 * - PUT /api/invoices/{id}/booking - Update booking fields, with per-field clearing
 * - PUT /api/invoices/{id}/review - Stamp the invoice as reviewed
 * - POST /api/invoices/{id}/cancel - Cancel the invoice by a correction (Storno), never a delete
 *
 * Throws PosException for parameter-validation failures; the global StatusPages handler converts
 * PosException to application/problem+json.
 */
package org.example.project.features.document.domain.routes

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
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.document.domain.service.InvoiceCancellationService
import org.example.project.features.document.domain.service.InvoiceListService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * Invoice API routes using a single authentication wrapper.
 *
 * All endpoints require JWT authentication; `RoutePermissions` gates both writes to MANAGER.
 */
fun Route.invoiceRoutes() {
    val documentManagementService by inject<DocumentManagementService>()
    val invoiceListService by inject<InvoiceListService>()
    val invoiceCancellationService by inject<InvoiceCancellationService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/invoices") {
            /**
             * List the Rechnungen work queue, one bucket at a time.
             *
             * Description: Requires MANAGER role. ZU_ERFASSEN lists uploaded invoice documents no invoice has been extracted from yet, while ZU_PRUEFEN and FERTIG partition the extracted invoices by whether a human has confirmed them. The response always carries the counts of all six buckets, so an empty bucket reads as zero rather than as a missing figure.
             *
             * Tag: invoices
             * Query: bucket Which bucket to list — ZU_ERFASSEN, ZU_PRUEFEN, FERTIG, OFFEN, UEBERFAELLIG or UEBERZAHLT. Defaults to ZU_ERFASSEN.
             * Query: limit Page size, 1 to 500. Defaults to 200.
             * Query: offset Rows to skip. Defaults to 0.
             * Response: 200 The rows of one bucket plus the counts of all six.
             * Response: 400 bucket is not one of the six names.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val bucket = call.bucketParam()
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(HttpStatusCode.OK, invoiceListService.list(bucket, limit, offset))
            }

            /**
             * Read one invoice by its own id.
             *
             * Description: Requires MANAGER role. The invoice-keyed counterpart of GET /api/documents/{id}/invoice, and the only way to reach an invoice that has no source document — one that arrived as a bare XRechnung XML, or was entered by hand. Returns the whole aggregate: lines, VAT breakdown and booking.
             *
             * Tag: invoices
             * Path: id Invoice identifier.
             * Response: 200 The invoice aggregate.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No invoice with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid invoice ID",
                        )

                call.respond(HttpStatusCode.OK, documentManagementService.getInvoice(id))
            }

            /**
             * Replace an invoice's supplier-asserted header.
             *
             * Description: Requires MANAGER role. Optionally replaces the VAT breakdown too. This is a full replace and not a patch — an omitted field is written as null. Returns 409 when the invoice carries EINVOICE_XML provenance, because that content came from the supplier's own XML and a typed replacement holds nothing better; booking is unaffected and goes through the sibling endpoint.
             *
             * Tag: invoices
             * Path: id Invoice identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No invoice with that id.
             * Response: 409 The invoice content is read-only because it came from an e-invoice XML.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid invoice ID",
                        )

                val request = call.receive<DocumentDto.InvoiceContentRequestDto>()
                val response = documentManagementService.updateInvoiceSupplierContent(id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Update only an invoice's booking fields.
             *
             * Description: Requires MANAGER role. A null field means leave unchanged, and clearing is said out loud through clearFields. Naming a field there and giving it a value at the same time is a 400.
             *
             * Tag: invoices
             * Path: id Invoice identifier.
             * Response: 400 id is not a number, or a field is both cleared and given a value.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No invoice with that id.
             */
            put("/{id}/booking") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid invoice ID",
                        )

                val request = call.receive<DocumentDto.UpdateInvoiceBookingRequestDto>()
                val response = documentManagementService.updateInvoiceBooking(id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Confirm an invoice's content.
             *
             * Description: Requires MANAGER role. Stamps the review timestamp, moving the invoice out of ZU_PRUEFEN into FERTIG. Idempotent — re-confirming keeps the first stamp, because that is the confirmation an auditor asks about.
             *
             * Tag: invoices
             * Path: id Invoice identifier.
             * Response: 200 The reviewed row, in the FERTIG bucket.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No invoice with that id.
             */
            put("/{id}/review") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid invoice ID",
                        )

                call.respond(HttpStatusCode.OK, invoiceListService.markReviewed(id))
            }

            /**
             * Cancel an incoming invoice by a correction (Storno).
             *
             * Description: Requires INVOICE_WRITE. The operator's way out of a Beleg that carries an invoice, which is never deleted: the original invoice and its Beleg stay archived, and a correction — a second invoice with the totals negated, type code 381, BT-25 naming the original and the reason as its booking text — neutralises it. Afterwards neither counts as open (supplier totals, Rechnungen OFFEN, Zuordnung) and neither is passed to the DATEV AP export; both are read-only. Audited as CANCEL_INCOMING_INVOICE. Returns the correction.
             *
             * Tag: invoices
             * Path: id Invoice identifier.
             * Response: 201 The correction, whose storno field names the original.
             * Response: 400 id is not a number, or the reason is blank or longer than 60 characters.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks INVOICE_WRITE.
             * Response: 404 No invoice with that id.
             * Response: 409 INVOICE_NOT_CANCELLABLE — already cancelled, itself a correction, without a Beleg, or allocated or marked paid.
             */
            post("/{id}/cancel") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid invoice ID",
                        )

                val request = call.receive<DocumentDto.CancelInvoiceRequestDto>()
                val correction = invoiceCancellationService.cancel(id, request, call.getEmployeeIdFromJwt())
                call.respond(HttpStatusCode.Created, correction)
            }
        }
    }
}

/** The `bucket` query parameter, defaulting to the queue an operator opens the screen for. */
private fun ApplicationCall.bucketParam(): InvoiceListDto.Bucket {
    val raw = request.queryParameters["bucket"] ?: return InvoiceListDto.Bucket.ZU_ERFASSEN
    return InvoiceListDto.Bucket.entries.firstOrNull { it.name == raw }
        ?: throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Unknown bucket '$raw'",
        )
}
