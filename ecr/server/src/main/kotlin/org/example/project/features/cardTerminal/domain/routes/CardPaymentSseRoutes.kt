package org.example.project.features.cardTerminal.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.cardTerminal.CardPaymentEventDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.requireMayBillAs
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.cardTerminal.domain.service.CardPaymentService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CardPaymentSseRoutes")

fun Route.cardPaymentSseRoutes() {
    val cardPaymentService by inject<CardPaymentService>()
    val billService by inject<BillService>()

    authenticate("auth-jwt") {
        route("/api/bills") {
            /**
             * Create a bill and take a card payment, streaming progress.
             *
             * Description: Requires MANAGER or CASHIER role. Returns a text/event-stream rather than a single response, because a card payment takes many seconds — the client receives an intermediate status, then either a bill-created plus success event, or a failed event naming the phase that broke. Every parameter travels as a query parameter, including the bill request as JSON in `request`, since EventSource cannot send a body; `cardTerminalId` and `amountCents` are required and `currency` defaults to EUR. The stream always answers 200 once it opens, so payment failure is reported in the event body, not the status code. The terminal is aborted on the way out whatever happened.
             *
             * Tag: card-payments
             * Response: 400 cardTerminalId, amountCents or request is missing or malformed.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not a MANAGER or CASHIER, or the bill would be registered under the order station's shared account — refused before the card is charged.
             */
            get("/create-with-card") {
                // Parse query parameters
                val terminalId =
                    call.request.queryParameters["cardTerminalId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid cardTerminalId",
                        )
                val requestJson =
                    call.request.queryParameters["request"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing request payload",
                        )
                val currency = (call.request.queryParameters["currency"] ?: "EUR").uppercase()

                // Decode token claims
                val tokenClaims = call.getTokenClaims() ?: return@get call.respond(HttpStatusCode.Unauthorized)

                // No bill under the station account (OS-b). Checked HERE, before the card is charged:
                // `createBill` refuses too, but only after authorization, which would leave a charged
                // card with no bill behind it. Attribution, not device: a person's own login on the
                // station device takes a card payment like anywhere else.
                dbQuery {
                    requireMayBillAs(tokenClaims.employeeId)
                }

                // Deserialize bill request
                val billRequest: BillDto.CreateBillRequestDto =
                    try {
                        Json.decodeFromString<BillDto.CreateBillRequestDto>(requestJson)
                    } catch (e: Exception) {
                        logger.error("Invalid bill create request payload", e)
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid bill request payload",
                        )
                    }

                // Amount in cents is expected per plan (simplified for this MVP)
                val amountCents =
                    call.request.queryParameters["amountCents"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing amountCents query parameter",
                        )

                // Create bill and link payment
                val employeeId = tokenClaims.employeeId
                val deviceId = tokenClaims.deviceId

                // Start streaming status events
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        // Emit a single initial intermediate status for simplicity
                        val init = CardPaymentEventDto.IntermediateStatusEvent(code = 64, message = "Please insert card")
                        val initJson = Json.encodeToString(init)
                        // No surrounding quotes: the payload is already a JSON object, and
                        // wrapping it made this the one event in the stream that would not parse.
                        write("data: ${initJson}\n\n")
                        flush()

                        // Perform payment authorization
                        val tx =
                            try {
                                cardPaymentService.authorizePayment(terminalId, amountCents, currency)
                            } catch (e: PosException) {
                                val failed =
                                    CardPaymentEventDto.FailedEvent(
                                        error = e.message ?: e.code.name,
                                        phase = "authorization",
                                    )
                                val f = Json.encodeToString(failed)
                                write("data: ${f}\n\n")
                                flush()
                                return@respondTextWriter
                            }

                        // Create bill and link payment if possible
                        val bill =
                            try {
                                billService.createBill(employeeId, deviceId, billRequest)
                            } catch (e: Exception) {
                                logger.warn("Bill creation failed in card payment SSE flow: ${e.message}")
                                null
                            }
                        if (bill != null) {
                            val paymentId = bill.payments.firstOrNull()?.id
                            if (paymentId != null) {
                                try {
                                    cardPaymentService.linkTransactionToPayment(tx.id, paymentId)
                                } catch (e: Exception) {
                                    logger.warn("Failed to link card transaction ${tx.id} to payment $paymentId: ${e.message}")
                                }
                            }
                            val billCreated =
                                CardPaymentEventDto.BillCreatedEvent(billId = bill.billId, billNumber = bill.billNumber)
                            val billJson = Json.encodeToString(billCreated)
                            write("data: ${billJson}\n\n")
                            flush()
                            // Emit success event
                            val success = CardPaymentEventDto.CardPaymentSuccessEvent(cardTransactionId = tx.id)
                            val successJson = Json.encodeToString(success)
                            write("data: ${successJson}\n\n")
                            flush()
                        } else {
                            val failed =
                                CardPaymentEventDto.FailedEvent(error = "Bill creation failed", phase = "payment")
                            val f = Json.encodeToString(failed)
                            write("data: ${f}\n\n")
                            flush()
                        }
                    } catch (e: Exception) {
                        logger.error("Error in card payment SSE flow", e)
                        val failed =
                            CardPaymentEventDto.FailedEvent(
                                error = (e.message ?: "Unknown error"),
                                phase = "exception",
                            )
                        val f = Json.encodeToString(failed)
                        write("data: ${f}\n\n")
                        flush()
                    } finally {
                        try {
                            cardPaymentService.abortPayment(terminalId)
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }
                }
            }
        }
    }
}
