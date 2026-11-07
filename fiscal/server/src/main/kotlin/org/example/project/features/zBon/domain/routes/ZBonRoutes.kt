package org.example.project.features.zBon.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.heldCapabilities
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.printer.domain.service.PrinterService
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.printer.core.formatter.ZBonFormatter
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ZBonRoutes")

/**
 * Z-report (ZBon) API routes.
 *
 * The service throws [PosException]; the global StatusPages handler converts to
 * application/problem+json. Routes only do parameter validation and delegate.
 */
fun Route.zBonRoutes() {
    val service by inject<ZBonService>()
    val printerService by inject<PrinterService>()
    val zBonFormatter by inject<ZBonFormatter>()
    val kassenbuchService by inject<KassenbuchService>()
    authenticate("auth-jwt") {
        route("/api/z-bon") {
            /** GET /api/z-bon — all Z-reports. */
            /**
             * List all Z-reports.
             *
             * Description: Open to any authenticated employee, unlike the manager-gated detail routes beside it. Returns every Z-Bon the till has closed.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                logger.info("Getting all Z-reports")
                call.respond(HttpStatusCode.OK, service.getAllReports())
            }

            /** GET /api/z-bon/{id} — single Z-report. */
            /**
             * Get a Z-report.
             *
             * Description: Requires the capability RoutePermissions names for this route. The full Z-Bon including its VAT split, payment breakdown and TSE data.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No Z-report with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid Z-report ID",
                        )
                logger.info("Getting Z-report with ID: $id")
                call.respond(HttpStatusCode.OK, service.getReportById(id, true))
            }

            /** GET /api/z-bon/history?startDate&endDate — summaries. */
            /**
             * List Z-report summaries for a period.
             *
             * Description: Requires the capability RoutePermissions names for this route. Both `startDate` and `endDate` query parameters are required.
             *
             * Tag: z-bon
             * Response: 400 startDate or endDate is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/history") {
                val startDate = call.request.queryParameters["startDate"]
                val endDate = call.request.queryParameters["endDate"]

                if (startDate == null || endDate == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Both startDate and endDate query parameters are required",
                    )
                }

                logger.info("Getting Z-Bon history from $startDate to $endDate")
                call.respond(HttpStatusCode.OK, service.getZBonHistory(startDate, endDate))
            }

            /** GET /api/z-bon/latest/datetime — latest endTime, or fallback. */
            /**
             * Get the last Z-report's closing time.
             *
             * Description: Open to any authenticated employee — the till uses it to show when the current trading day began. When no Z-Bon has ever been closed it answers 200 with the 2020-01-01 fallback rather than 404.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token.
             */
            get("/latest/datetime") {
                logger.info("Getting latest Z-report datetime")
                val data = service.getLatestReportByShop()?.endTime
                if (data != null) {
                    call.respond(HttpStatusCode.OK, data)
                } else {
                    call.respond(
                        HttpStatusCode.OK,
                        LocalDateTime.parse("2020-01-01T00:00:00").toString(),
                    )
                }
            }

            /** GET /api/z-bon/recent?limit=5 — recent Z-report summaries. */
            /**
             * List the most recent Z-reports.
             *
             * Description: Requires the capability RoutePermissions names for this route. `limit` is clamped to 1–100 and defaults to 5.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/recent") {
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 100) ?: 5
                logger.info("Getting {} most recent Z-reports", limit)
                call.respond(HttpStatusCode.OK, service.getRecentReports(limit))
            }

            /** POST /api/z-bon — create new Z-report. */
            /**
             * Close the day and create a Z-report.
             *
             * Description: Requires the capability RoutePermissions names for this route. The daily fiscal close — it totals the day, signs the Z-Bon on the TSE, and moves the takings into the configured transfer cash book as a Geldtransit. Not reversible, and it defines the boundary of the trading day for the DSFinV-K export.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Optional body `{"countedAmount": "123.45", "tipPayouts": [{"employeeId": "<uuid>", "amount": "3.00"}]}`.
             * `countedAmount` is the drawer count; its Kassendifferenz is booked inside the closing's own
             * transaction, after the payouts, so a refused closing books nothing. `tipPayouts` are employee-tip payouts
             * booked inside the closing's own transaction — signed Kassenbuch withdrawals (TrinkgeldAN) that
             * belong to this closing and roll back with it; they need the Kassenbuch write capability on top,
             * and each must fit that employee's employee tips of the period being closed. The count is
             * compared against the drawer after the payouts. A payout with `"method": "BANK_PAYROLL"` (F59) moves no
             * cash: it only records that the tip was paid by bank/payroll — no Kassenbuch entry, no DSFinV-K row.
             * `topUp` `{"fromCashBookId": 2, "amount": "10.00"}` (F59) puts cash from another book (Tresor) into the
             * Ladenkasse as a signed Geldtransit inside the closing, before the cash payouts it covers.
             *
             * Response: 400 Open items remain on a table, or there is nothing to close, or a payout amount or employee id is malformed or an employee is named twice, or a top-up is malformed, comes from the Ladenkasse itself or an inactive book, or is sent without cash payouts (nothing is booked).
             * Response: 403 Payouts were requested without the Kassenbuch write capability (nothing is booked).
             * Response: 409 A Z-report for this period already exists, or open table sessions remain, or a payout exceeds the employee's tips of the period or names an employee without any; or KASSENBUCH_INSUFFICIENT_CASH — the cash payouts exceed the drawer (after any top-up), or the top-up's source book holds less than the top-up (nothing is booked).
             * Response: 409 ZBON_SIGNER_NOT_DESIGNATED / ZBON_SIGNER_UNAVAILABLE — the closing carries cash movements and no till can sign them (nothing is booked).
             * Response: 503 TSE signing failed and the shop is configured to fail the close.
             */
            post {
                logger.info("Creating new Z-report")

                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                // Older tills send no body; an empty body means "no count".
                val body =
                    if ((call.request.contentLength() ?: 0L) > 0L) {
                        call.receive<ZBonDto.CreateZBonRequestDto>()
                    } else {
                        ZBonDto.CreateZBonRequestDto()
                    }
                val report =
                    service.createReport(
                        employeeId = tokenClaims.employeeId,
                        deviceId = tokenClaims.deviceId,
                        countedAmount = body.countedAmount,
                        tipPayouts = body.tipPayouts,
                        heldCapabilities = call.heldCapabilities(),
                        topUp = body.topUp,
                    )
                call.respond(HttpStatusCode.Created, report)
            }

            /** GET /api/z-bon/open-period-tips — employee tips of the period the next Z-Bon closes. */
            /**
             * List the open period's employee tips.
             *
             * Description: Requires the capability RoutePermissions names for this route — the Kassenbuch read capability: the list books nothing, and paying the tips out inside the closing still requires the Kassenbuch write capability on POST /api/z-bon. One row per employee with employee tips (TrinkgeldAN) in the bills the next Z-Bon will close, scoped exactly as that closing scopes them: the total and its display-only cash/card split. The payout always hands out the total in cash; the drawer advances the card part.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/open-period-tips") {
                call.respond(HttpStatusCode.OK, service.getOpenPeriodTips())
            }

            /**
             * Report who would sign a closing triggered from this device.
             *
             * Description: Requires the capability RoutePermissions names for this route (ZBON_CLOSE). Reads only. The signer is the till designated on the Ladenkasse, else the calling till; `signerProblem` says why it cannot sign (NOT_DESIGNATED, INACTIVE, NOT_FISCAL, NOT_REGISTERED). With a problem, a closing may carry no count, tip payout or top-up, and is refused outright when `dailyTransferConfigured` is true.
             *
             * Tag: z-bon
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            get("/closing-readiness") {
                val claims = call.getTokenClaims() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(HttpStatusCode.OK, service.getClosingReadiness(claims.deviceId))
            }

            /** GET /api/z-bon/{id}/pdf — stored or regenerated PDF. */
            /**
             * Download a Z-report as PDF.
             *
             * Description: Requires the capability RoutePermissions names for this route. Serves the stored PDF, regenerating it if the file is missing, as a ZBon_{id}.pdf attachment.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No Z-report with that id.
             */
            get("/{id}/pdf") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid Z-Bon ID",
                        )

                val bytes = service.getZBonPdfBytes(id)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            "ZBon_$id.pdf",
                        ).toString(),
                )
                call.respondBytes(bytes, ContentType.Application.Pdf)
            }

            /** POST /api/z-bon/{id}/print — print a Z-Bon to the configured printer. */
            /**
             * Print a Z-report.
             *
             * Description: Requires the capability RoutePermissions names for this route. Sends the Z-Bon to the configured receipt printer; the response reports what the printer did.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No Z-report with that id.
             * Response: 502 The printer reported a failed print job (`PRINTER_UNAVAILABLE`); the message carries the printer's reason.
             * Response: 503 Printer settings are disabled, or the printer is unreachable.
             */
            post("/{id}/print") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid Z-Bon ID",
                        )

                val receipt = service.getReportById(id, true)
                // PrinterService throws PosException; StatusPages handles errors.
                val printResult = dbQuery { printerService.printZBon({ zBonFormatter.format(receipt, Language.GERMAN) }) }
                // Thrown after dbQuery returns so the failure cannot roll back the print audit row.
                if (!printResult.success) {
                    throw PosException(PosErrorCode.PRINTER_UNAVAILABLE, HttpStatusCode.BadGateway, printResult.message)
                }
                call.respond(HttpStatusCode.OK, printResult)
            }

            /** POST /api/z-bon/{id}/tip-payouts — pay an employee's tips from this closing in cash. */
            /**
             * Pay out an employee's tips from a closing.
             *
             * Description: Requires the capability RoutePermissions names for this route — the Kassenbuch write capability, because the payout is a cash withdrawal from the fiscal Ladenkasse. Books one TSE-signed Kassenbuch withdrawal dated now (DSFinV-K GV_TYP TrinkgeldAN, DATEV account for employee tips) and links it to the closing and the employee. Body `{"employeeId": "<uuid>", "amount": "12.50"}`; partial payouts are allowed, but their sum never exceeds the employee's employee-tip total in that closing. The response carries the booked Beleg and the employee's new remaining amount.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Response: 400 id is not a number, or the amount is not a positive decimal with at most two places, or employeeId is not a UUID.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires, or the device is not a fiscal terminal.
             * Response: 404 No Z-report or no employee with that id.
             * Response: 409 The amount exceeds what is still payable, or the employee has no tip in that closing, or the closing predates employee-tip bookings; or KASSENBUCH_INSUFFICIENT_CASH — a cash payout larger than the drawer holds.
             * With `"method": "BANK_PAYROLL"` (F59) the tip counts as paid by bank transfer or payroll: only the settlement is recorded — no Kassenbuch entry, no TSE signature, no DSFinV-K row, no DATEV booking (the tax adviser books it against the transit account) — and the response carries `payoutId` with null `kassenbuchHeaderId`/`belegNr`.
             */
            post("/{id}/tip-payouts") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid Z-Bon ID",
                        )
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ZBonDto.CreateTipPayoutRequestDto>()
                val result = kassenbuchService.payOutEmployeeTip(tokenClaims.employeeId, tokenClaims.deviceId, id, body)
                call.respond(HttpStatusCode.Created, result)
            }

            /** POST /api/z-bon/{id}/tip-payouts/{payoutHeaderId}/reverse — storno one tip payout. */
            /**
             * Storno a tip payout.
             *
             * Description: Requires the capability RoutePermissions names for this route — the same Kassenbuch write capability as the payout. Books an append-only, TSE-signed Kassenbuch correction (Storno) of the payout's withdrawal: cash back into the fiscal Ladenkasse, DSFinV-K TrinkgeldAN with a positive amount, an Eigenbeleg carrying the reason. The payout is never edited; its amount becomes payable again. A payout can be stornoed once. Body `{"reason": "..."}`.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Path: payoutHeaderId Kassenbuch entry id of the payout to storno.
             * Response: 400 an id is not a number, or the reason is blank.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires, or the device is not a fiscal terminal.
             * Response: 404 No Z-report with that id.
             * Response: 409 The entry is not a tip payout of that closing, or it was already stornoed.
             */
            post("/{id}/tip-payouts/{payoutHeaderId}/reverse") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid Z-Bon ID")
                val payoutHeaderId =
                    call.parameters["payoutHeaderId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid payout entry ID")
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ZBonDto.ReverseTipPayoutRequestDto>()
                val result = kassenbuchService.reverseEmployeeTipPayout(tokenClaims.employeeId, tokenClaims.deviceId, id, payoutHeaderId, body)
                call.respond(HttpStatusCode.Created, result)
            }

            /** POST /api/z-bon/{id}/tip-payouts/settlements/{payoutId}/reverse — storno one bank/payroll settlement. */
            /**
             * Storno a bank/payroll tip settlement.
             *
             * Description: Requires the capability RoutePermissions names for this route — the same Kassenbuch write capability as the payout. Reverses a BANK_PAYROLL settlement (F59): an append-only reversal row, no Kassenbuch entry (the settlement moved no cash, so its storno moves none). The amount becomes payable again. A settlement can be stornoed once. Body `{"reason": "..."}`.
             *
             * Tag: z-bon
             * Path: id Z-report identifier.
             * Path: payoutId Id of the settlement (`TipPayoutRecordDto.payoutId`).
             * Response: 400 an id is not a number, or the reason is blank.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No Z-report with that id.
             * Response: 409 The row is not a bank/payroll settlement of that closing (a cash payout is stornoed through `/tip-payouts/{payoutHeaderId}/reverse`), or it was already stornoed.
             */
            post("/{id}/tip-payouts/settlements/{payoutId}/reverse") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid Z-Bon ID")
                val payoutId =
                    call.parameters["payoutId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid settlement ID")
                val tokenClaims = call.getTokenClaims() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ZBonDto.ReverseTipPayoutRequestDto>()
                val result = kassenbuchService.reverseEmployeeTipSettlement(tokenClaims.employeeId, tokenClaims.deviceId, id, payoutId, body)
                call.respond(HttpStatusCode.Created, result)
            }
        }
    }
}
