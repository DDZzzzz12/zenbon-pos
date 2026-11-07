/**
 * REST API routes for Terminal registration and management.
 *
 * Provides HTTP endpoints for terminal registration, listing, and management.
 * Terminals are client devices that connect to the POS server.
 * Required for DSFinV-K multi-client compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.data.service.TlsRootCa
import org.example.project.features.terminal.domain.service.StationSessionService
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.DeviceTokenPrincipal
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TerminalRoutes")

// Same decoding leniency as the server's ContentNegotiation JSON (ContentNegotiation.kt).
private val pairingCodeJson =
    Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

/**
 * The pairing-code body is one optional flag (`{"registerTseClient":true}`, 25 bytes); anything far
 * past that is not a pairing-code request. Bounds the read so the body is never buffered in full.
 */
private const val PAIRING_CODE_BODY_LIMIT_BYTES = 4 * 1024L

/**
 * Header name for device ID.
 */
const val DEVICE_ID_HEADER = "X-Device-ID"

/**
 * Configures terminal-related API routes.
 */
fun Routing.terminalRoutes() {
    val terminalService by inject<TerminalService>()
    val stationSessionService by inject<StationSessionService>()

    /**
     * The TLS root certificate of this server's local CA.
     *
     * Description: Public and unauthenticated — the certificate is public by nature. A till that was handed the certificate's check code out of band (the pairing QR, or typed) downloads it here over a connection it cannot yet verify, accepts it only if it hashes to that code, and then trusts the server's HTTPS certificates. 404 when this server has no local CA (plain development server, or a publicly trusted certificate).
     *
     * Tag: terminals
     * Response: 404 No local CA root is configured.
     */
    get("/api/v1/terminals/root-ca") {
        val root =
            TlsRootCa.current()
                ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "No local CA root certificate")
        call.respondText(root.pem, ContentType.parse("application/x-pem-file"))
    }

    /**
     * Pair a device to a terminal.
     *
     * Description: Public and unauthenticated — pairing is authorised by the six-digit pairing code itself, since the device has no employee login yet. Rate-limited per source IP, because a six-digit numeric code is brute-forceable inside its lifetime without that guard. On success the device receives its device token. A code issued with registerTseClient also registers the terminal's TSE client before the device is bound: a client the TSE already lists is only recorded as registered, one it does not list is registered under the admin sitting the code was issued in. If that registration fails nothing is bound and the code stays usable. A code issued without it makes no TSE call.
     *
     * Tag: terminals
     * Response: 400 PAIRING_CODE_INVALID — no unused code matches (unknown, or already redeemed); PAIRING_CODE_EXPIRED — the code is past its expiry, or the admin sitting that issued it has ended; or the request body failed validation.
     * Response: 403 AUTH_FORBIDDEN — a web-admin client tried to pair as a terminal.
     * Response: 409 TERMINAL_NEEDS_PAIRING — the code's terminal is deactivated.
     * Response: 424 PAIRING_TSE_REGISTRATION_FAILED — the code asks for TSE registration and the TSE could not register the client; nothing was bound and the code is still valid.
     * Response: 429 Too many pairing attempts from this address.
     */
    post("/api/v1/terminals/pair") {
        val ip = call.request.origin.remoteAddress
        if (!PairAttemptLimiter.checkAllowed(ip)) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.TooManyRequests,
                "Too many pairing attempts",
            )
        }
        val request = call.receive<TerminalDto.PairDeviceRequestDto>()
        // Same guard as /register. Without it the admin-web check there is bypassable: pairing
        // binds a device id to a terminal and issues a device token, so a back-office browser
        // could become a terminal by pairing rather than registering. The composeApp browser
        // preview is unaffected — it uses its own POS_PREVIEW_PREFIX id, not this one.
        if (DeviceIdentifiers.isAdminWebDevice(request.deviceId)) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "Web admin clients cannot be paired to terminals",
            )
        }
        val dto =
            try {
                terminalService.pairDevice(request)
            } catch (e: PosException) {
                if (e.code == PosErrorCode.PAIRING_CODE_INVALID || e.code == PosErrorCode.PAIRING_CODE_EXPIRED) {
                    PairAttemptLimiter.recordFailure(ip)
                }
                throw e
            }
        PairAttemptLimiter.recordSuccess(ip)
        call.respond(HttpStatusCode.OK, dto)
    }

    /**
     * GET /api/v1/terminals/me-by-device - Current terminal for a paired device.
     *
     * Authenticated by the device token (X-Device-Token header). Used by the composeApp launch
     * gate to validate the stored token and read the terminal role without an employee login.
     */
    authenticate("device-principal") {
        /**
         * Get this device's terminal.
         *
         * Description: Authenticated by the device token in the X-Device-Token header rather than an employee JWT — the launch gate calls it before anyone has logged in, to check the stored token and read the terminal's role.
         *
         * Tag: terminals
         * Response: 401 Missing or invalid device token.
         * Response: 404 No terminal is bound to this device.
         * Response: 500 The bound terminal carries no physical device id.
         */
        get("/api/v1/terminals/me-by-device") {
            val principal =
                call.principal<DeviceTokenPrincipal>()
                    ?: throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Unauthorized,
                        "Device principal required",
                    )
            val deviceId =
                principal.cap.deviceId
                    ?: throw PosException(
                        PosErrorCode.INTERNAL_ERROR,
                        HttpStatusCode.InternalServerError,
                        "Bound terminal has no physical device id",
                    )
            val dto = terminalService.getCurrentTerminalByDevice(deviceId)
            call.respond(HttpStatusCode.OK, dto)
        }

        /**
         * Open a station session.
         *
         * Description: Authenticated by the device token in the X-Device-Token header — no JWT, no body. A paired ORDER_STATION exchanges its device token for a short-lived access JWT carrying a `station` claim, under which it runs the till shell; the response also carries the masked capability set so the client can seed its gates. No refresh token is issued — the station renews by calling this again. The same terminal ceiling as POST /api/orders runs first.
         *
         * Tag: terminals
         * Response: 401 The device token has expired.
         * Response: 403 The terminal is deactivated or non-fiscal, or its role has no station shell (CASHIER, KITCHEN, CUSTOMER_DISPLAY, KIOSK).
         * Response: 409 The device is not paired.
         * Response: 424 TSE mode is NORMAL and the terminal's TSE client is not registered.
         * Response: 500 The shared terminal-device employee is not provisioned.
         */
        post("/api/v1/terminals/session") {
            val principal =
                call.principal<DeviceTokenPrincipal>()
                    ?: throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Unauthorized,
                        "Device principal required",
                    )
            val dto = stationSessionService.openSession(principal.cap)
            call.respond(HttpStatusCode.OK, dto)
        }
    }

    authenticate("auth-jwt") {
        route("/api/v1/terminals") {
            /**
             * Register or update this terminal.
             *
             * Description: Identifies the device by the required X-Device-ID header. The web admin console's synthetic device id is refused outright — a browser is not a fiscal terminal and must never be registrable as one. Registering an already-known device updates its record instead of creating a second one.
             *
             * Tag: terminals
             * Response: 400 The X-Device-ID header is missing, or the body is malformed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 The caller is a web admin client, which cannot be a terminal.
             */
            post("/register") {
                val userId = call.getEmployeeIdFromJwt()
                val deviceId = call.request.header(DEVICE_ID_HEADER)
                if (deviceId.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Missing $DEVICE_ID_HEADER header",
                    )
                }
                // The wasm admin console is a browser, not a fiscal terminal. Reject
                // its synthetic device id so it can never be registered as a terminal.
                if (DeviceIdentifiers.isAdminWebDevice(deviceId)) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Web admin clients cannot be registered as terminals",
                    )
                }

                val request =
                    try {
                        call.receive<TerminalDto.RegisterTerminalRequestDto>()
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid request format",
                        )
                    }

                logger.info("Terminal registration request from device: $deviceId")
                val dto = terminalService.registerOrUpdate(userId ?: "", deviceId, request)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get this terminal's record.
             *
             * Description: Identifies the device by the required X-Device-ID header and returns the terminal registered for it.
             *
             * Tag: terminals
             * Response: 400 The X-Device-ID header is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No terminal is registered for this device.
             */
            get("/me") {
                val deviceId = call.request.header(DEVICE_ID_HEADER)
                if (deviceId.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Missing $DEVICE_ID_HEADER header",
                    )
                }

                val dto = terminalService.getTerminalByDeviceId(deviceId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Whether pairing can register a TSE client.
             *
             * Description: Requires MANAGER role. Reports the TSE mode and whether the device is commissioned, and whether a pairing code may therefore carry registerTseClient. The back office reads it to default and enable its "register at the TSE while pairing" box. Declared before the id routes so Ktor cannot read tse-pairing-status as a terminal id.
             *
             * Tag: terminals
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/tse-pairing-status") {
                call.respond(HttpStatusCode.OK, terminalService.getTsePairingStatus())
            }

            /**
             * Sync every terminal's TSE registration.
             *
             * Description: Requires MANAGER role. Reconciles each terminal's stored TSE client registration against the device and reports the refreshed set. Declared before the id route so Ktor cannot read sync-tse as a terminal id.
             *
             * Tag: terminals
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 TSE_DISABLED — the TSE is disabled; put it into operation first.
             * Response: 423 TSE_ADMIN_LOCKED — the TSE's Admin sitting is locked; enter the Admin PIN and retry.
             * Response: 424 The Swissbit SDK is not installed on this server.
             * Response: 500 The TSE returned an unsuccessful result without reporting an error.
             * Response: 503 The TSE could not be reached.
             */
            post("/sync-tse") {
                logger.info("Syncing TSE registration status for all terminals")
                val dtos = terminalService.syncTseRegistrationStatus()
                call.respond(HttpStatusCode.OK, dtos)
            }

            /**
             * List terminals.
             *
             * Description: Requires MANAGER role. Every registered terminal with its device binding and TSE registration state — the DSFinV-K multi-client picture.
             *
             * Tag: terminals
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val dtos = terminalService.getAllTerminals()
                call.respond(HttpStatusCode.OK, dtos)
            }

            /**
             * Provision an unbound terminal.
             *
             * Description: Requires MANAGER role. Creates the terminal record ahead of the hardware, so a pairing code can be issued and the device bound later.
             *
             * Tag: terminals
             * Response: 400 The request body failed validation, or `role` names no terminal role (`VALIDATION_FAILED` with a `role` field error). An absent `role` means CASHIER.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 A terminal with that name or number already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<TerminalDto.CreateTerminalRequestDto>()
                val dto = terminalService.createTerminal(userId ?: "", request)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Edit a terminal's name, location and role.
             *
             * Description: Requires MANAGER role. A null field is left unchanged. `fiscal` cannot be changed here — whether a terminal signs is what DSFinV-K and the TSE client registration rest on, so provision a new terminal instead of flipping an existing one.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is missing or not a number, or the role is not a known TerminalRole.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             */
            put("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val terminalId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID format",
                        )
                val request = call.receive<TerminalDto.UpdateTerminalRequestDto>()
                val dto = terminalService.updateTerminal(userId ?: "", terminalId, request)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Deactivate a terminal.
             *
             * Description: Requires MANAGER role. Nothing is erased — the terminal is flagged inactive so the receipts it signed keep resolving their client id, as DSFinV-K requires. Use reactivate to bring it back.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is missing, or is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             */
            delete("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val terminalIdParam =
                    call.parameters["id"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Terminal ID is required",
                        )

                val terminalId =
                    terminalIdParam.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID format",
                        )

                logger.info("Deactivating terminal ID: $terminalId")
                terminalService.deactivateTerminal(userId ?: "", terminalId)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Terminal deactivated successfully"),
                )
            }

            /**
             * Register a terminal with the TSE.
             *
             * Description: Requires MANAGER role. Registers the terminal as a TSE client, which it must be before it can sign anything. Idempotent — a terminal the device already lists comes back unchanged. The database flag alone is not trusted: a terminal flagged registered that the attached device does not list (after a restore or a TSE swap) is registered again.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is missing, or is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             * Response: 409 TSE_DISABLED — the TSE is disabled; put it into operation first.
             * Response: 423 TSE_ADMIN_LOCKED — the TSE's Admin sitting is locked; enter the Admin PIN and retry.
             * Response: 424 The Swissbit SDK is not installed on this server.
             * Response: 500 The TSE rejected the client registration.
             */
            post("/{id}/tse-register") {
                val terminalIdParam =
                    call.parameters["id"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Terminal ID is required",
                        )

                val terminalId =
                    terminalIdParam.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID format",
                        )

                logger.info("Registering terminal $terminalId with TSE")
                val dto = terminalService.registerTerminalTse(terminalId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Deregister a retired terminal from the TSE.
             *
             * Description: Requires MANAGER role. Removes the terminal's client ID from the TSE's list of registered clients, freeing a slot under the device's client limit. Permanent for that client ID until it is registered again with `tse-register`; the receipts it already signed are unaffected. Refused unless the terminal is deactivated, has no bound device, and has no unfinished TSE transactions — on the device or pending in the database. A NORMAL-mode TSE keeps at least one registered client, because its self test needs one. Writes a `DEREGISTER_TSE_CLIENT` audit row.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is missing, or is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             * Response: 409 TSE_DISABLED — the TSE is disabled; VALIDATION_FAILED — the terminal is still active, still bound to a device, not registered at the TSE, has unfinished TSE transactions, or is the last client of a NORMAL-mode TSE.
             * Response: 423 TSE_ADMIN_LOCKED — the TSE's Admin sitting is locked; enter the Admin PIN and retry.
             * Response: 424 The Swissbit SDK is not installed on this server.
             * Response: 422 The TSE refused the deregistration.
             */
            post("/{id}/tse-deregister") {
                val userId = call.getEmployeeIdFromJwt()
                val terminalId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID format",
                        )

                logger.info("Deregistering terminal $terminalId from the TSE")
                val dto = terminalService.deregisterTerminalTse(userId ?: "", terminalId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Reactivate a terminal.
             *
             * Description: Requires MANAGER role. Restores a deactivated terminal so it can take orders again.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is missing, or is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             */
            post("/{id}/reactivate") {
                val userId = call.getEmployeeIdFromJwt()
                val terminalIdParam =
                    call.parameters["id"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Terminal ID is required",
                        )

                val terminalId =
                    terminalIdParam.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid terminal ID format",
                        )

                logger.info("Reactivating terminal ID: $terminalId")
                val dto = terminalService.reactivateTerminal(userId ?: "", terminalId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Generate a pairing code.
             *
             * Description: Requires the TERMINAL_MANAGE capability. Issues a short-lived six-digit code the device types in to bind itself to this terminal. The code expires, and the public pair endpoint is rate-limited against guessing. The body is optional: registerTseClient (default false) makes pairing register the terminal's TSE client. That needs a fiscal terminal and a TSE in NORMAL mode and commissioned, and — unless the TSE already lists the client — an unlocked TSE admin sitting; the code is then bound to that sitting and dies when it ends. Without it the code pairs without touching the TSE, and TSE registration stays a separate step. An empty body — with or without a Content-Type or Content-Length — means the defaults, like `{}`.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 INVALID_REQUEST — id is not a number, or a non-empty body is not a valid pairing-code request (malformed JSON, or larger than 4 KiB).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks TERMINAL_MANAGE.
             * Response: 404 No terminal with that id.
             * Response: 409 registerTseClient was asked for and cannot be honoured — TERMINAL_NOT_FISCAL — the terminal does not sign; TSE_DISABLED — the TSE is disabled; TSE_NOT_OPERATIONAL — the TSE is in simulation or not commissioned.
             * Response: 423 TSE_ADMIN_LOCKED — registerTseClient for a client the TSE does not list needs the Admin sitting; enter the Admin PIN and retry.
             */
            post("/{id}/pairing-code") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a number",
                        )
                val request = call.receivePairingCodeRequest()
                call.respond(
                    HttpStatusCode.Created,
                    terminalService.generatePairingCode(userId ?: "", id, request.registerTseClient),
                )
            }

            /**
             * Revoke a terminal's device binding.
             *
             * Description: Requires MANAGER role. Invalidates the device token, so a lost or replaced device stops working immediately and the terminal can be paired to new hardware.
             *
             * Tag: terminals
             * Path: id Terminal identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No terminal with that id.
             */
            post("/{id}/revoke") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "id must be a number",
                        )
                call.respond(HttpStatusCode.OK, terminalService.revokeBinding(userId ?: "", id))
            }
        }
    }
}

/**
 * Reads the OPTIONAL pairing-code body. No body at all — however it arrives: Content-Length 0, no
 * Content-Length (`curl -X POST`, a chunked client), with or without a Content-Type — means the
 * defaults, exactly like `{}`; older clients send none, which means "do not register at the TSE".
 * The text is read and decoded here rather than through ContentNegotiation, which refuses an empty
 * body with no Content-Type as `CannotTransformContentToTypeException` (a 500) and an empty JSON
 * body as a 400. A non-empty body that is not a valid request is a 400, and so is one past
 * [PAIRING_CODE_BODY_LIMIT_BYTES]: at most one byte more than the limit is read.
 */
private suspend fun ApplicationCall.receivePairingCodeRequest(): TerminalDto.PairingCodeRequestDto {
    val bytes = receiveChannel().readRemaining(PAIRING_CODE_BODY_LIMIT_BYTES + 1).readByteArray()
    if (bytes.size > PAIRING_CODE_BODY_LIMIT_BYTES) {
        throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Request body too large")
    }
    val text = bytes.decodeToString()
    if (text.isBlank()) return TerminalDto.PairingCodeRequestDto()
    return try {
        pairingCodeJson.decodeFromString<TerminalDto.PairingCodeRequestDto>(text)
    } catch (e: SerializationException) {
        throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid request format", cause = e)
    }
}
