/**
 * REST API routes for Ausgangsrechnungen (outgoing invoices).
 *
 * Routes delegate; the service owns every rule. `PosException` reaches the global StatusPages
 * plugin, which renders RFC 7807 `application/problem+json` — so no handler here catches anything,
 * and none re-checks a guard the service already enforces.
 */
package org.example.project.features.salesinvoice.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceDatevExportService
import org.example.project.features.salesinvoice.domain.service.SalesInvoiceService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SalesInvoiceRoutes")

fun Route.salesInvoiceRoutes() {
    val service by inject<SalesInvoiceService>()
    val datevExportService by inject<SalesInvoiceDatevExportService>()

    authenticate("auth-jwt") {
        route("/api/v1/sales-invoices") {
            /**
             * Create a draft invoice for a settled till bill.
             *
             * Description: Requires MANAGER role. Snapshots the bill's items, its fiscal VAT split and its TSE signature block onto a new draft. Only a standing sale can be invoiced: a Storno, a cancelled original (including one rebooked by a Zahlartwechsel) or a receivable clearing is refused with 409 BILL_NOT_A_STANDING_SALE. A Neubuchung (Zahlartwechsel) is a sale in its own right and is invoiced like any other. Also refuses a training bill, one inconsistent with its own VAT details, or one that already has a live invoice.
             *
             * Tag: sales-invoices
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 The bill does not exist.
             * Response: 409 The bill is not a standing sale (BILL_NOT_A_STANDING_SALE), cannot be invoiced, or already has a live invoice.
             */
            post("/from-bill") {
                val request = call.receive<SalesInvoiceDto.CreateFromBillRequestDto>()
                request.validate().firstOrNull()?.let {
                    throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, it)
                }
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val created = service.createFromBillAsDto(request, claims.employeeUuid())
                logger.info("Created draft invoice ${created.id} for bill ${request.billId}")
                call.respond(HttpStatusCode.Created, created)
            }

            /**
             * Create a standalone draft invoice for revenue that never touched the till.
             *
             * Description: Requires MANAGER role. This is the case-C path — the invoice has no originating till bill. Derives net and VAT per line from the gross prices, sums the VAT breakdown, and snapshots the seller block from shop_info. The draft consumes no invoice number; issuing it does.
             *
             * Tag: sales-invoices
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 DEBTOR_INACTIVE — the chosen debtor is deactivated.
             */
            post("/standalone") {
                val request = call.receive<SalesInvoiceDto.CreateStandaloneRequestDto>()
                request.validate().firstOrNull()?.let {
                    throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, it)
                }
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val created = service.createStandaloneAsDto(request, claims.employeeUuid())
                logger.info("Created standalone draft invoice ${created.id}")
                call.respond(HttpStatusCode.Created, created)
            }

            /**
             * Full-replace the content of a draft.
             *
             * Description: Requires MANAGER role. Rejects an ISSUED invoice with 409 — an issued invoice changes only through a 381 Gutschrift.
             *
             * Tag: sales-invoices
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             * Response: 409 The invoice is already issued or cancelled.
             */
            put("/{id}") {
                val request = call.receive<SalesInvoiceDto.UpdateStandaloneRequestDto>()
                request.validate().firstOrNull()?.let {
                    throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, it)
                }
                val claims = call.getTokenClaims() ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val updated = service.updateStandaloneAsDto(call.invoiceId(), request)
                call.respond(HttpStatusCode.OK, updated)
            }

            /**
             * Delete a draft. Nothing legal happened, so a discarded draft leaves no trace.
             *
             * Description: Requires MANAGER role. Rejects an ISSUED invoice with 409 — issuing a Gutschrift is the only way out of ISSUED.
             *
             * Tag: sales-invoices
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             * Response: 409 The invoice is already issued or cancelled.
             */
            delete("/{id}") {
                val claims = call.getTokenClaims() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                service.deleteStandalone(call.invoiceId())
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * List invoices for the Ausgangsrechnungen buckets.
             *
             * Description: Requires MANAGER role. `status` filters DRAFT/ISSUED/CANCELLED and `type` filters the UNTDID 1001 code — `380` for invoices, `381` for Gutschriften. `debtorId` narrows to one customer's invoices, which is what the Debitoren detail pane shows; an invoice with no debtor is a walk-in sale and never matches one. `open` narrows to a positive residual and `overdue` additionally requires the due day to be over (strictly before today; an invoice due today is not yet overdue). Newest first.
             *
             * Tag: sales-invoices
             * Response: 400 An unknown status was supplied.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                call.respond(
                    HttpStatusCode.OK,
                    service.listAsDto(
                        status = call.request.queryParameters["status"],
                        invoiceTypeCode = call.request.queryParameters["type"],
                        open = call.request.queryParameters["open"].toBoolean(),
                        overdue = call.request.queryParameters["overdue"].toBoolean(),
                        debtorId = call.request.queryParameters["debtorId"]?.toLongOrNull(),
                        limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50,
                        offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L,
                    ),
                )
            }

            /**
             * Read one invoice with its lines and VAT breakdown.
             *
             * Description: Requires MANAGER role.
             *
             * Tag: sales-invoices
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             */
            get("/{id}") {
                call.respond(HttpStatusCode.OK, service.getAsDto(call.invoiceId()))
            }

            /**
             * Issue a draft — validate it, consume a §14 number, freeze it and archive its PDF.
             *
             * Description: Requires MANAGER role. Irreversible — an issued invoice can only be left through a 381 Gutschrift. A rejected issuance consumes no number.
             *
             * Tag: sales-invoices
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             * Response: 409 The invoice is already issued or cancelled, its debtor is deactivated (DEBTOR_INACTIVE), or a concurrent issuance held the year's number counter (SALES_INVOICE_NUMBER_COLLISION — nothing was consumed; retry).
             * Response: 422 The invoice's content is incomplete or its totals do not add up.
             */
            post("/{id}/issue") {
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val issued = service.issueAsDto(call.invoiceId(), claims.employeeUuid())
                logger.info("Issued invoice ${issued.id} as ${issued.invoiceNumber}")
                call.respond(HttpStatusCode.OK, issued)
            }

            /**
             * Settle an open receivable at the counter.
             *
             * Description: Requires MANAGER role. Takes a payment in cash or by card against a POS_RECEIVABLE invoice, writes a TSE-signed clearing receipt and reduces the invoice's open amount. Refuses a tender that would itself create a receivable, and refuses an overpayment.
             *
             * Tag: sales-invoices
             * Response: 200 The invoice, with its reduced open amount.
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             * Response: 409 Not an issued POS_RECEIVABLE invoice, a receivable-creating tender, or an overpayment.
             */
            post("/{id}/settle-at-till") {
                val request = call.receive<SalesInvoiceDto.SettleAtTillRequestDto>()
                request.validate().firstOrNull()?.let {
                    throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, it)
                }
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val settled =
                    service.settleAtTill(call.invoiceId(), request, claims.employeeUuid(), claims.deviceId)
                logger.info("Settled receivable ${settled.id} at the till, open now ${settled.openAmount}")
                call.respond(HttpStatusCode.OK, settled)
            }

            /**
             * Cancel an issued invoice by issuing a 381 Gutschrift.
             *
             * Description: Requires MANAGER role. Returns the Gutschrift, which carries its own number and negated amounts; the original becomes CANCELLED and its bill slot is freed.
             *
             * Tag: sales-invoices
             * Response: 400 The id is not a number, or the reason is missing, blank or longer than 60 characters (the reason is printed on the Gutschrift; no audit row is written for this refusal).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice.
             * Response: 409 The invoice is not ISSUED, so there is nothing to reverse, or a concurrent issuance held the year's number counter (SALES_INVOICE_NUMBER_COLLISION — nothing was consumed; retry).
             */
            post("/{id}/cancel") {
                val request = call.receive<SalesInvoiceDto.CancelRequestDto>()
                val claims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val storno = service.cancelAsDto(call.invoiceId(), claims.employeeUuid(), request.reason)
                logger.info("Cancelled invoice via Gutschrift ${storno.invoiceNumber}")
                call.respond(HttpStatusCode.OK, storno)
            }

            /**
             * Download the archived PDF of an issued invoice.
             *
             * Description: Requires MANAGER role. Serves the file stored at issue — it is never re-rendered, so the bytes match the copy the customer holds.
             *
             * Tag: sales-invoices
             * Response: 400 The id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No such invoice, or it is still a draft and has no archived document.
             * Response: 500 The archived file is missing from storage.
             */
            get("/{id}/pdf") {
                call.respondBytes(service.reprint(call.invoiceId()), ContentType.Application.Pdf)
            }

            /**
             * Preflight for the DATEV Debitoren (accountsReceivableLedger) export.
             *
             * Description: Requires ADMIN role. Counts and totals the issued STANDALONE invoices in `[start, end]` and lists any whose Personenkonto/Erlöskonto do not resolve, without producing a file.
             *
             * Tag: sales-invoices
             * Response: 400 `start` or `end` is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/export/datev/statistics") {
                val start = call.request.queryParameters["start"] ?: throw validationFailed("start is required")
                val end = call.request.queryParameters["end"] ?: throw validationFailed("end is required")
                call.respond(HttpStatusCode.OK, datevExportService.statistics(start, end))
            }

            /**
             * Build the DATEV Debitoren (accountsReceivableLedger) ZIP for issued STANDALONE invoices.
             *
             * Description: Requires ADMIN role. A manual, on-demand download — not part of the auto-catchup cadence. Refuses with 409 rather than emit a partial archive if any invoice's accounts do not resolve.
             *
             * Tag: sales-invoices
             * Response: 400 `start` or `end` is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 409 At least one invoice's Personenkonto/Erlöskonto does not resolve.
             */
            get("/export/datev") {
                val claims = call.getTokenClaims() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val start = call.request.queryParameters["start"] ?: throw validationFailed("start is required")
                val end = call.request.queryParameters["end"] ?: throw validationFailed("end is required")
                val zip = datevExportService.export(start, end, claims.employeeUuid())
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=datev-ar-$start" + "_" + "$end.zip")
                call.respondBytes(zip, ContentType.Application.Zip)
            }
        }
    }
}

/** A `400 VALIDATION_FAILED` for a malformed/missing request parameter. */
private fun validationFailed(msg: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, msg)

/**
 * The caller's employee id as a UUID.
 *
 * `TokenClaims.employeeId` is a `String`; issuing writes it to `Document.uploadedBy`, which is a real
 * FK, so a malformed claim must fail loudly here rather than at the archival insert.
 */
private fun org.example.project.core.security.TokenClaims.employeeUuid(): java.util.UUID =
    runCatching { java.util.UUID.fromString(employeeId) }.getOrElse {
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Unauthorized,
            "The token carries no usable employee id",
        )
    }

/** The `{id}` path parameter, or a 400 — every route in this file needs it. */
private fun ApplicationCall.invoiceId(): Long =
    parameters["id"]?.toLongOrNull()
        ?: throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "The invoice id must be a number",
        )
