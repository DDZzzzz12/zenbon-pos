package org.example.project.features.cardTerminal.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.cardTerminal.CardTerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.domain.service.CardTerminalService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CardTerminalRoutes")

fun Route.cardTerminalRoutes() {
    val service by inject<CardTerminalService>()

    authenticate("auth-jwt") {
        route("/api/card-terminals") {
            /**
             * List card terminals.
             *
             * Description: Every configured payment terminal with its connection state. Any authenticated employee may read it, since the POS must offer the operator a terminal to pay on.
             *
             * Tag: card-terminals
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                call.respond(HttpStatusCode.OK, service.getAllTerminals())
            }

            /**
             * List available card terminals.
             *
             * Description: Only terminals currently connected and idle — the set the payment screen may actually route a transaction to.
             *
             * Tag: card-terminals
             * Response: 401 Missing or invalid bearer token.
             */
            get("/available") {
                call.respond(HttpStatusCode.OK, service.getAvailableTerminals())
            }

            /**
             * Get a card terminal by ID.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No card terminal with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.getTerminalById(id))
            }

            /**
             * Register a card terminal.
             *
             * Description: Requires ADMIN role. Records the terminal's address and protocol settings; connecting to it is a separate step.
             *
             * Tag: card-terminals
             * Response: 400 The request body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 409 A card terminal with that address is already registered.
             */
            post("/") {
                val request =
                    try {
                        call.receive<CardTerminalDto.CreateCardTerminalRequestDto>()
                    } catch (e: Exception) {
                        logger.error("Invalid create terminal request", e)
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request body format",
                        )
                    }
                call.respond(HttpStatusCode.Created, service.registerTerminal(request))
            }

            /**
             * Update a card terminal.
             *
             * Description: Requires ADMIN role. Only the fields present in the body are changed. Changing the address of a connected terminal takes effect on the next connect.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number, or the body is malformed or failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No card terminal with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                val request =
                    try {
                        call.receive<CardTerminalDto.UpdateCardTerminalRequestDto>()
                    } catch (e: Exception) {
                        logger.error("Invalid update terminal request", e)
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request body format",
                        )
                    }
                call.respond(HttpStatusCode.OK, service.updateTerminal(id, request))
            }

            /**
             * Delete a card terminal.
             *
             * Description: Requires ADMIN role. Card transactions already recorded keep their own copy of the terminal identity, so removing the configuration does not affect them.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No card terminal with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                service.deleteTerminal(id)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Connect to a card terminal.
             *
             * Description: Requires MANAGER role. Opens the session with the device — the start-of-day step before it can take payments.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No card terminal with that id.
             * Response: 503 The terminal did not answer.
             */
            post("/{id}/connect") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.connectTerminal(id))
            }

            /**
             * Disconnect from a card terminal.
             *
             * Description: Requires MANAGER role. Closes the session with the device.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No card terminal with that id.
             */
            post("/{id}/disconnect") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.disconnectTerminal(id))
            }

            /**
             * Get a card terminal's live status.
             *
             * Description: Open to any authenticated employee — the payment screen polls it to tell the cashier whether the terminal is ready. It queries the device, so it is a POST rather than a cacheable GET.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No card terminal with that id.
             * Response: 503 The terminal did not answer.
             */
            post("/{id}/status") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.getTerminalStatus(id))
            }

            /**
             * Run a card terminal diagnosis.
             *
             * Description: Requires MANAGER role. Triggers the device's own diagnosis routine, which the acquirer requires periodically, and returns what it reported.
             *
             * Tag: card-terminals
             * Path: id Card terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No card terminal with that id.
             * Response: 503 The terminal did not answer.
             */
            post("/{id}/diagnosis") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID",
                        )
                call.respond(HttpStatusCode.OK, service.runDiagnosis(id))
            }
        }
    }
}
