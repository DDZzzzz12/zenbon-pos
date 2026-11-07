package org.example.project.features.cardTerminal.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.domain.service.CardSettlementService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.cardSettlementRoutes() {
    val service by inject<CardSettlementService>()

    authenticate("auth-jwt") {
        route("/api/card-terminals") {
            /**
             * Preview a card terminal's totals.
             *
             * Description: Requires MANAGER role. Asks the terminal what it believes it has taken since its last settlement, so the figure can be compared against the till before closing the day.
             *
             * Tag: card-settlements
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No card terminal with that id.
             * Response: 503 The terminal did not answer.
             */
            get("/{id}/totals-preview") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.previewTotals(id))
            }
        }

        route("/api/card-settlements") {
            /**
             * List card settlements for a Z-report.
             *
             * Description: Requires MANAGER role. The terminal settlements belonging to one Z-Bon. The `zbonId` query parameter is required.
             *
             * Tag: card-settlements
             * Response: 400 The zbonId query parameter is missing or not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/") {
                val zbonId =
                    call.request.queryParameters["zbonId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Missing or invalid zbonId query parameter",
                        )
                call.respond(HttpStatusCode.OK, service.getSettlementsByZbon(zbonId))
            }

            /**
             * Get the card reconciliation report.
             *
             * Description: Requires MANAGER role. Compares what the card terminals settled against what the till booked as card payments for that Z-Bon, which is where an acquirer discrepancy shows up.
             *
             * Tag: card-settlements
             * Path: id Z-report identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No Z-report with that id.
             */
            get("/{id}/reconciliation") {
                val zbonId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid zbonId",
                        )
                call.respond(HttpStatusCode.OK, service.getReconciliationReport(zbonId))
            }
        }
    }
}
