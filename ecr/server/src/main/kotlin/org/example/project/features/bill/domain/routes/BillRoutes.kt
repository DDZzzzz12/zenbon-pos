package org.example.project.features.bill.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.heldCapabilities
import org.example.project.features.bill.domain.service.BillService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("BillRoutes")

/**
 * Bill API routes. Migrated from Result matching to PosException-throws in Stage B Batch 2.
 */
fun Route.billRoutes() {
    val service by inject<BillService>()

    authenticate("auth-jwt") {
        route("/api/v1/bills") {
            /**
             * List all bills.
             *
             * Description: Any authenticated employee may read bills. Cancelled (storno'd) bills are hidden unless `includeDeleted=true` is passed — they are never removed, since a fiscal document must stay retrievable.
             *
             * Tag: bills
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                logger.info("Getting all bills")
                val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, service.getAllBills(includeDeleted))
            }

            /**
             * Get a bill by ID.
             *
             * Description: Returns the full bill including its lines, payments, VAT split and TSE signature data.
             *
             * Tag: bills
             * Path: id Bill identifier.
             * Response: 400 id is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No bill with that id.
             */
            get("/{id}") {
                val billId =
                    call.parameters["id"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid bill ID format")

                logger.info("Getting bill by ID: $billId")
                call.respond(HttpStatusCode.OK, service.getBillById(billId))
            }

            /**
             * Get a bill by its bill number.
             *
             * Description: Lookup by the printed receipt number, which is what an operator or auditor has in hand when tracing a document.
             *
             * Tag: bills
             * Path: billNumber Printed bill number.
             * Response: 400 billNumber is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No bill carries that number.
             */
            get("/number/{billNumber}") {
                val billNumber = call.parameters["billNumber"]
                if (billNumber.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Bill number is required")
                }

                logger.info("Getting bill by number: $billNumber")
                call.respond(HttpStatusCode.OK, service.getBillByNumber(billNumber))
            }

            /**
             * Get a bill by its TSE transaction number.
             *
             * Description: Reverse lookup from a TSE transaction back to the receipt it signed — the path a tax auditor takes when reconciling the TSE log against the till.
             *
             * Tag: bills
             * Path: transactionNumber TSE transaction number.
             * Response: 400 transactionNumber is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No bill carries that TSE transaction number.
             */
            get("/tse-transaction/{transactionNumber}") {
                val transactionNumber =
                    call.parameters["transactionNumber"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid TSE transaction number format",
                        )

                logger.info("Getting bill by TSE transaction number: $transactionNumber")
                call.respond(HttpStatusCode.OK, service.getBillByTseTransactionNumber(transactionNumber))
            }

            /**
             * List the bills of a table session.
             *
             * Description: A session can carry several bills when the guests split the table. Pass `includeDeleted=true` to include storno'd bills.
             *
             * Tag: bills
             * Path: tableSessionId Table session identifier.
             * Response: 400 tableSessionId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/table-session/{tableSessionId}") {
                val tableSessionId =
                    call.parameters["tableSessionId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid table session ID format")

                logger.info("Getting bills for table session: $tableSessionId")
                val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, service.getBillsByTableSessionId(tableSessionId, includeDeleted))
            }

            /**
             * Search bills.
             *
             * Description: Every filter is an optional query parameter — `employeeNumber`, `tableSessionId`, `startDate` and `endDate` as ISO-8601 date-times, `minAmount` and `maxAmount` as decimal strings, `tipRecipient` as EMPLOYEE or OWNER, `includeDeleted`, and `includeTraining` (default false) to add Schulungsbelege — each then carries `isTraining = true` for a list to badge; a receipt list of a Z-Bon asks for them, a revenue view must not. Results page with `offset` (default 0) and `limit` (default 50).
             *
             * Tag: bills
             * Response: 400 A date is not ISO-8601, an amount is not a decimal, or tipRecipient is not EMPLOYEE or OWNER.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/search") {
                logger.info("Searching bills with criteria")

                val employeeId = call.request.queryParameters["employeeNumber"]
                val tableSessionId = call.request.queryParameters["tableSessionId"]?.toLongOrNull()
                val startDate =
                    call.request.queryParameters["startDate"]?.let {
                        try {
                            LocalDateTime.parse(it)
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid start date format. Use ISO 8601 format.",
                            )
                        }
                    }
                val endDate =
                    call.request.queryParameters["endDate"]?.let {
                        try {
                            LocalDateTime.parse(it)
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid end date format. Use ISO 8601 format.",
                            )
                        }
                    }
                val minAmount =
                    call.request.queryParameters["minAmount"]?.let {
                        try {
                            BigDecimal(it)
                        } catch (e: Exception) {
                            throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid minimum amount format")
                        }
                    }
                val maxAmount =
                    call.request.queryParameters["maxAmount"]?.let {
                        try {
                            BigDecimal(it)
                        } catch (e: Exception) {
                            throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid maximum amount format")
                        }
                    }
                val tipRecipient = call.request.queryParameters["tipRecipient"]
                val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false
                val includeTraining = call.request.queryParameters["includeTraining"]?.toBoolean() ?: false
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val results =
                    service.searchBills(
                        employeeId = employeeId,
                        tableSessionId = tableSessionId,
                        startDate = startDate,
                        endDate = endDate,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        tipRecipient = tipRecipient,
                        includeDeleted = includeDeleted,
                        offset = offset,
                        limit = limit,
                        includeTraining = includeTraining,
                    )
                call.respond(HttpStatusCode.OK, results)
            }

            /**
             * List bill summaries.
             *
             * Description: Lightweight rows for the bill-history list — number, time, total and status, without lines or payments. Pages with `offset` (default 0) and `limit` (default 50); `includeDeleted=true` adds storno'd bills.
             *
             * Tag: bills
             * Response: 401 Missing or invalid bearer token.
             */
            get("/summaries") {
                logger.info("Getting bill summaries")

                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false

                call.respond(HttpStatusCode.OK, service.getBillSummaries(offset, limit, includeDeleted))
            }

            /**
             * Get bill statistics for a period.
             *
             * Description: Aggregates turnover over a window. Both the `startDate` and `endDate` query parameters are required and must be ISO-8601 date-times; `includeDeleted=true` counts storno'd bills as well.
             *
             * Tag: bills
             * Response: 400 startDate or endDate is missing, or is not ISO-8601.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/statistics") {
                logger.info("Getting bill statistics")

                val startDate =
                    call.request.queryParameters["startDate"]?.let {
                        try {
                            LocalDateTime.parse(it)
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid start date format. Use ISO 8601 format.",
                            )
                        }
                    }
                val endDate =
                    call.request.queryParameters["endDate"]?.let {
                        try {
                            LocalDateTime.parse(it)
                        } catch (e: Exception) {
                            throw PosException(
                                PosErrorCode.INVALID_REQUEST,
                                HttpStatusCode.BadRequest,
                                "Invalid end date format. Use ISO 8601 format.",
                            )
                        }
                    }

                if (startDate == null || endDate == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Start date and end date are required for statistics",
                    )
                }

                val includeDeleted = call.request.queryParameters["includeDeleted"]?.toBoolean() ?: false

                call.respond(HttpStatusCode.OK, service.getBillStatistics(startDate, endDate, includeDeleted))
            }

            /**
             * Print a bill.
             *
             * Description: Requires the `PRINTER_WRITE` capability, which every floor group holds — reprinting a receipt for a guest is till work, not a fiscal act, and nothing here is signed on the TSE. Reprints an already settled bill on the receipt printer. Pass `bewirtungsbeleg=true` to print the German hospitality-expense variant instead of the plain receipt. The Bewirtungsbeleg prints only for the original of a standing sale — a Zahlartwechsel correction, a Storno bill, a cancelled original or a receivable clearing is refused with 409 BILL_NOT_A_STANDING_SALE; the plain reprint stays open for every bill.
             *
             * Tag: bills
             * Path: billId Bill identifier.
             * Response: 400 billId is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold `PRINTER_WRITE`.
             * Response: 404 No bill with that id, or the printing employee no longer exists.
             * Response: 409 `bewirtungsbeleg=true` on a bill that is not a standing sale (BILL_NOT_A_STANDING_SALE).
             * Response: 409 Printing is disabled for this shop (`PRINTING_DISABLED`); no print job is created.
             * Response: 502 The printer reported a failed print job (`PRINTER_UNAVAILABLE`); the message carries the printer's reason.
             */
            post("/{billId}/print") {
                val billId =
                    call.parameters["billId"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Bill ID is required")
                val employeeId = call.getEmployeeIdFromJwt() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val bewirtungsbeleg = call.request.queryParameters["bewirtungsbeleg"]?.toBoolean() ?: false

                service.printBill(billId, employeeId, bewirtungsbeleg)
                call.respond(HttpStatusCode.OK)
            }

            /**
             * Create a bill.
             *
             * Description: Requires ADMIN, MANAGER or CASHIER role. Settles the listed order items — the fiscal act. It reserves a gapless bill number, locks the items, records the payments and signs the receipt on the TSE. A voucher payment's redeemed amount must equal the voucher payment amount exactly, or the DSFinV-K export would disagree with itself.
             *
             * Tag: bills
             * Response: 400 The body is malformed, the item list is empty, or a voucher has insufficient balance.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN, MANAGER or CASHIER, or the bill would be registered under the order station's shared account (its station token or device token, with or without a step-up) — nothing is billed under it. A person's own login bills on any device.
             * Response: 404 The session, or one of the order items in it, does not exist.
             * Response: 409 An item is not open, or a unique bill number could not be reserved.
             * Response: 422 A voucher redemption amount does not equal its voucher payment amount.
             * Response: 503 TSE signing failed and the shop is configured to fail the sale.
             */
            post {
                logger.info("Creating new bill")

                val request =
                    try {
                        call.receive<BillDto.CreateBillRequestDto>()
                    } catch (e: Exception) {
                        logger.error("Invalid request body for bill creation", e)
                        throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid request body format")
                    }
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val bill = service.createBill(tokenClaims.employeeId, tokenClaims.deviceId, request)
                call.respond(HttpStatusCode.Created, bill)
            }

            /**
             * Cancel a bill (Belegstorno).
             *
             * Description: Requires ADMIN or MANAGER role — cashiers may not storno. Nothing is erased: the bill is flagged deleted and a TSE-signed reversal document is written, which the DSFinV-K export carries. Pass the operator's justification in the `reason` query parameter.
             *
             * Tag: bills
             * Path: id Bill identifier.
             * Query: reason Optional operator's justification.
             * Query: restoreSession Optional, default true — re-open the bill's table session and copy its items back for re-billing. false leaves session and table untouched (plain Storno).
             * Response: 400 id is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN or MANAGER, or the caller is the order station's shared account (nothing is billed under it, not even under a step-up).
             * Response: 404 No bill with that id.
             * Response: 409 The bill is already cancelled, or (TABLE_HAS_ACTIVE_SESSION) restoreSession=true but new guests now occupy the table — retry with restoreSession=false.
             * Response: 503 TSE signing of the reversal failed and the shop is configured to fail the operation.
             */
            delete("/{id}") {
                val billId =
                    call.parameters["id"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid bill ID format")

                val deletingReason = call.request.queryParameters["reason"]
                val restoreSession = call.request.queryParameters["restoreSession"]?.toBooleanStrictOrNull() ?: true

                logger.info("Soft deleting bill: $billId with reason: $deletingReason")
                val tokenClaims = call.getTokenClaims() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                service.deleteBill(tokenClaims.employeeId, tokenClaims.deviceId, billId, deletingReason, restoreSession)
                call.respond(HttpStatusCode.OK, "Bill deleted successfully")
            }

            /**
             * Change a bill's tender or tip (Zahlartwechsel) by Storno + Neubuchung.
             *
             * Description: Requires `BILL_CHANGE_PAYMENT` (ADMIN, MANAGER and CASHIER hold it; a manager step-up lends it). Keeping or raising the tip needs nothing more; lowering it also needs `BILL_VOID`. In one transaction the bill is stornoed — a TSE-signed Storno Beleg with the Storno reason `ZAHLARTWECHSEL`, refunding its payments and restoring any voucher it redeemed — and its items are booked again at once as a new bill with the requested payments, voucher redemptions and whole tip. The new bill is booked under the ORIGINAL seller, keeps the original's tip recipient and training flag, re-applies the original's promotions as of the original's close, and is linked to the original through `relations.rebooksBill` (`relations.kind` = `REBOOKING`). Both Belege are signed by the TSE (or queued, following the shop's failure behaviour), Storno first, so each printed Beleg matches its own signature. The table and its session are not touched. The payments must equal the bill total after promotions plus `tips`. Returns the new bill.
             *
             * Tag: bills
             * Path: id Bill identifier of the standing sale to rebook.
             * Response: 400 id is missing or the body is malformed (`INVALID_REQUEST`); a payment amount or `tips` is not a number, a payment is not positive or finer than a cent, or `tips` is negative (`VALIDATION_FAILED`); or the payments do not equal the bill total after promotions plus `tips` (`PAYMENT_TOTAL_MISMATCH`).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks `BILL_CHANGE_PAYMENT`; the tip would be lowered and the caller lacks `BILL_VOID` (`BILL_REBOOK_TIP_DECREASE_FORBIDDEN`); or the caller is the order station's shared account (nothing is billed under it, not even under a step-up).
             * Response: 404 No bill with that id, or a requested payment type does not exist.
             * Response: 409 The bill is already cancelled or rebooked (`BILL_ALREADY_DELETED`); it is a Storno Beleg (`BILL_NOT_A_STANDING_SALE`); it sells a gift voucher or clears an invoice (`BILL_REBOOK_UNSUPPORTED`); the tender, vouchers and tip equal the bill's own (`BILL_REBOOK_NO_CHANGE`); a promotion on it changed since the sale (`BILL_REBOOK_PROMOTION_CHANGED`); an invoice was issued for it (`SALES_INVOICE_IMMUTABLE`); its business day is already closed by a Z-Bon; or a unique bill number could not be reserved.
             * Response: 422 A voucher redemption amount does not equal its voucher payment amount.
             * Response: 503 TSE signing of the Storno or the new bill failed and the shop is configured to fail the operation (both Belege are already committed).
             */
            post("/{id}/rebook") {
                val billId =
                    call.parameters["id"]
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid bill ID format")

                val request =
                    try {
                        call.receive<BillDto.RebookRequestDto>()
                    } catch (e: Exception) {
                        logger.error("Invalid request body for bill rebook", e)
                        throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid request body format")
                    }

                logger.info("Rebooking bill: $billId")
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val bill = service.rebookBill(tokenClaims.employeeId, tokenClaims.deviceId, billId, request, call.heldCapabilities())
                call.respond(HttpStatusCode.OK, bill)
            }
        }
    }
}
