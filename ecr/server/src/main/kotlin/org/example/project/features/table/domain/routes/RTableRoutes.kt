/**
 * Restaurant table API routes.
 *
 * Routes delegate to the service layer. Services throw [org.example.project.infrastructure.errors.PosException]
 * on errors; the global StatusPages handler converts to RFC 7807 ProblemDetail responses.
 *
 * Migrated from Result<T, Error> matching in Stage B Batch 10 commit 4/4.
 */
package org.example.project.features.table.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.datetime.LocalDateTime
import kotlinx.io.readByteArray
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.requireValidReason
import org.example.project.features.auth.data.overrideActorId
import org.example.project.features.order.data.service.RotationNextNotifier
import org.example.project.features.table.domain.service.RTableService
import org.example.project.features.table.domain.service.TableSessionService
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime

private val logger = LoggerFactory.getLogger("RTableRoutes")

/**
 * Configures restaurant table management routes.
 */
@OptIn(ExperimentalTime::class)
fun Routing.rTableRoutes() {
    val rTableService by inject<RTableService>()
    val tableSessionService by inject<TableSessionService>()
    val capabilityResolver by inject<TerminalCapabilityResolver>()

    authenticate("auth-jwt") {
        route("/api/tables") {
            /**
             * Get a table's current session.
             *
             * Description: A pure read: returns the table's open session and never creates one. A free table is opened by its first order (`POST /api/orders` with `tableId`), in the transaction that books the items. LEGACY, removed in the release after next: a caller whose token belongs to a paired terminal still gets the old get-or-create behaviour, because tills built before 2026-09-19 open a table this way before their first order; `forEmployeeNumber` then names the new session's owner. Virtual tables (quick sale, voucher) are never opened here.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Response: 400 id is not a positive number, the token carries no employee id, or `forEmployeeNumber` names a terminal account.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 TABLE_HAS_NO_SESSION — the table has no open session (not a paired terminal); or no table with that id, or the named employee does not exist.
             * Response: 409 TABLE_VIRTUAL_NOT_ORDERABLE — the legacy branch was asked to open a virtual table; or an order station tried to open a free table without employee rotation, to name the owner, or to open a takeaway or virtual table; or the rotation pool is empty.
             */
            get("/{id}/currentSession") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (id <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }

                val employeeId =
                    call.getTokenClaims()?.employeeId
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid employee ID",
                        )
                val forEmployeeNumber = call.request.queryParameters["forEmployeeNumber"]
                val deviceId = call.getTokenClaims()?.deviceId?.takeIf { it.isNotBlank() && it != "unknown" }
                val isPairedTerminal = deviceId != null && capabilityResolver.byDeviceId(deviceId)?.isActive == true
                if (isPairedTerminal) {
                    // LEGACY (remove in the release after next): tills built before 2026-09-19 open a
                    // table by GETting it before their first order. New tills open it with the order
                    // itself (POST /api/orders with tableId). Everyone else gets a pure read.
                    logger.warn(
                        "Deprecated: GET currentSession may open table {} for device {}; the first order opens a table",
                        id,
                        deviceId,
                    )
                    call.respond(HttpStatusCode.OK, rTableService.getOrCreateSessionByTableId(id, employeeId, forEmployeeNumber))
                } else {
                    val session =
                        rTableService.getCurrentSessionOrNull(id)
                            ?: throw PosException(
                                PosErrorCode.TABLE_HAS_NO_SESSION,
                                HttpStatusCode.NotFound,
                                "Table $id has no active session",
                            )
                    call.respond(HttpStatusCode.OK, session)
                }
            }

            /**
             * Get the next employee in the round-robin rotation.
             *
             * Description: A pure read: returns the employee who would be assigned the next free table, plus the version of the rotation notification stream, and never advances the rotation. `next` is null when round-robin assignment is off or the rotation pool is empty. Clients use the version to discard stale `rotation_next_update` push notifications.
             *
             * Tag: tables
             * Response: 401 Missing or invalid bearer token.
             */
            get("/rotation/next") {
                call.respond(HttpStatusCode.OK, RotationNextNotifier.snapshot())
            }

            /**
             * Search tables.
             *
             * Description: Type-ahead lookup by table number or name. The search term comes from the required `q` query parameter. `limit` is clamped to 1–20 (default 5), and `includeInactive=true` widens the search to deactivated tables.
             *
             * Tag: tables
             * Response: 400 The q query parameter is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/search") {
                val query = call.request.queryParameters["q"]
                if (query.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Query parameter 'q' is required",
                    )
                }
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 20) ?: 5
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false

                val response = rTableService.searchTables(query, limit, includeInactive)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * List tables.
             *
             * Description: The floor plan's data source. Optional query parameters narrow it — `status` to one TableStatus value, `locationTypeId` to one room, `includeInactive=true` to include deactivated tables, and `limit`/`offset` to page.
             *
             * Tag: tables
             * Response: 400 status is not a valid table status, or locationTypeId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()
                val status =
                    call.request.queryParameters["status"]?.let { statusParam ->
                        try {
                            TableStatus.valueOf(statusParam.uppercase())
                        } catch (_: IllegalArgumentException) {
                            throw PosException(
                                PosErrorCode.VALIDATION_FAILED,
                                HttpStatusCode.BadRequest,
                                "Invalid status parameter. Valid values: ${TableStatus.values().joinToString()}",
                            )
                        }
                    }
                val locationTypeId =
                    call.request.queryParameters["locationTypeId"]?.let { param ->
                        param.toLongOrNull() ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid locationTypeId parameter: must be a numeric ID",
                        )
                    }
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false

                logger.debug(
                    "Getting tables: limit=$limit offset=$offset status=$status locationTypeId=$locationTypeId includeInactive=$includeInactive",
                )
                val response = rTableService.getAllTables(limit, offset, status, locationTypeId, includeInactive)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Create a table.
             *
             * Description: Requires the capability RoutePermissions names for this route. The table number must be unique within the shop. The acting employee is taken from the JWT.
             *
             * Tag: tables
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 409 A table with that number already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<RTableDto.CreateRTableRequestDto>()
                logger.info("Creating table: ${request.tableNumber}")
                val response = rTableService.createTable(userId, request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * List all combinable table pairs.
             *
             * Description: Combinable pairs are the tables staff may push together for a larger party. Returns the whole symmetric pair set.
             *
             * Tag: tables
             * Response: 401 Missing or invalid bearer token.
             */
            get("/combinable") {
                val pairs = rTableService.getAllCombinablePairs()
                call.respond(HttpStatusCode.OK, pairs.map { RTableDto.CombinablePairDto(it.first, it.second) })
            }

            /**
             * Replace the whole combinable-pair set.
             *
             * Description: Requires the capability RoutePermissions names for this route. The submitted list becomes the complete set — pairs not present are removed. Returns the stored set.
             *
             * Tag: tables
             * Response: 400 The body failed validation, or a table id is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            put("/combinable") {
                val request = call.receive<RTableDto.SetCombinablePairsRequestDto>()
                val pairs =
                    rTableService.setAllCombinablePairs(
                        request.pairs.map { it.tableAId to it.tableBId },
                    )
                call.respond(HttpStatusCode.OK, pairs.map { RTableDto.CombinablePairDto(it.first, it.second) })
            }

            /**
             * List one table's combinable partners.
             *
             * Tag: tables
             * Path: id Table identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No table with that id.
             */
            get("/{id}/combinable") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                call.respond(HttpStatusCode.OK, rTableService.getCombinablePartners(id))
            }

            /**
             * Replace one table's combinable partners.
             *
             * Description: Requires the capability RoutePermissions names for this route. The body is a bare JSON array of partner table ids, and it replaces the table's current partner set. Pairings are symmetric — the partners gain this table too.
             *
             * Tag: tables
             * Path: id Table identifier.
             * Response: 400 id is not a number, or the body is not a JSON array of table ids.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No table with that id, or a partner id is unknown.
             */
            put("/{id}/combinable") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                val partnerIds =
                    try {
                        call.receive<List<Long>>()
                    } catch (_: Exception) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Expected JSON array of table IDs",
                        )
                    }
                call.respond(HttpStatusCode.OK, rTableService.setCombinablePartners(id, partnerIds))
            }

            /**
             * Get a table by ID.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No table with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (id <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                call.respond(HttpStatusCode.OK, rTableService.getTableById(id))
            }

            /**
             * Update a table.
             *
             * Description: Requires the capability RoutePermissions names for this route. Only the fields present in the body are changed — number, name, seat count, location type and floor-plan geometry.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Response: 400 id is not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No table with that id.
             * Response: 409 Another table already carries the new table number.
             */
            put("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (id <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                val request = call.receive<RTableDto.UpdateRTableRequestDto>()
                logger.info("Updating table: $id")
                val response = rTableService.updateTable(userId, id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Move several tables on the floor plan.
             *
             * Description: Requires the capability RoutePermissions names for this route. Takes the positions produced by the floor-plan editor's drag-and-drop and writes them in one batch. The response reports how many of the requested updates were applied.
             *
             * Tag: tables
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            post("/batch-update-positions") {
                val request = call.receive<RTableDto.BatchTablePositionUpdateDto>()
                logger.info("Batch updating positions for ${request.updates.size} tables")
                val updatedCount = rTableService.batchUpdateTablePositions(request)
                call.respond(
                    HttpStatusCode.OK,
                    RTableDto.BatchUpdatePositionsResponseDto(
                        success = true,
                        updatedCount = updatedCount,
                        requestedCount = request.updates.size,
                    ),
                )
            }

            /**
             * Delete a table.
             *
             * Description: Requires the capability RoutePermissions names for this route. A table carrying an open session cannot be deleted — settle or delete the session first. Tables referenced by historical sessions are deactivated rather than removed.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No table with that id.
             * Response: 409 The table still has an active session.
             */
            delete("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (id <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                logger.info("Deleting table: $id")
                rTableService.deleteTable(userId, id)
                call.respond(HttpStatusCode.OK, "Table deleted successfully")
            }

            /**
             * Move a whole session to another table.
             *
             * Description: Requires the capability RoutePermissions names for this route. Moves the source table's open session — all its items — onto the target table, for a party that changed seats. Both tables are locked in a fixed order to keep concurrent moves safe.
             *
             * Tag: tables
             * Path: id Identifier of the table the guests are leaving. Must be a positive number.
             * Response: 400 id is not a positive number, the body failed validation, or the target employee is a terminal account (a terminal account can never own a table session, on every transfer branch including moving items onto an occupied table).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 The source or target table does not exist.
             * Response: 409 The source table has no active session, or the target session is closed.
             */
            post("/{id}/transfer") {
                // On an order station under a manager's temporary sign-in the move is the manager's
                // (owner decision 2026-10-03, emu21 NOTE-v); everywhere else, the caller's.
                val claims = call.getTokenClaims()
                val managerId = claims?.overrideActorId() ?: call.getEmployeeIdFromJwt()
                val sourceTableId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (sourceTableId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                val request = call.receive<RTableDto.TransferTableRequestDto>()
                logger.info("Transferring table $sourceTableId to ${request.targetTableId}")
                val response = rTableService.transferTable(sourceTableId, request, managerId, deviceId = claims?.deviceId)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Delete a table's open session.
             *
             * Description: Requires the capability RoutePermissions names for this route. Discards the open session and frees the table, for a session opened by mistake. The acting employee and device are taken from the JWT and recorded.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Query: reason Why the session is deleted. Required, not blank, at most 500 characters; it is recorded on every item cancelled with the session.
             * Response: 400 id is not a positive number, or reason is missing, blank or longer than 500 characters.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the capability this route requires.
             * Response: 404 No table with that id.
             * Response: 409 The table has no active session to delete.
             */
            delete("/{id}/session") {
                val tableId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (tableId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                val tokenClaims =
                    call.getTokenClaims()
                        ?: run {
                            call.respond(HttpStatusCode.Unauthorized, "Invalid authentication token")
                            return@delete
                        }
                // A manager temporarily signed in on an order station is the actor there (NOTE-v).
                val actorId = tokenClaims.overrideActorId()
                val reason =
                    try {
                        call.request.queryParameters["reason"].requireValidReason()
                    } catch (e: IllegalArgumentException) {
                        throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, e.message ?: "A reason is required")
                    }
                logger.info("Deleting table session for table $tableId by employee $actorId")
                rTableService.deleteTableSession(tableId, actorId, tokenClaims.deviceId, reason)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Table session deleted successfully"))
            }

            /**
             * Get the customer assigned to a session.
             *
             * Description: Returns the customer and delivery address linked to the session, which the POS shows on delivery and account tickets.
             *
             * Tag: table-sessions
             * Path: sessionId Table session identifier. Must be a positive number.
             * Response: 400 sessionId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No session with that id.
             */
            get("/sessions/{sessionId}/customer") {
                val sessionId =
                    call.parameters["sessionId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid session ID",
                        )
                if (sessionId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid session ID",
                    )
                }
                call.respond(HttpStatusCode.OK, tableSessionService.getCustomerAndAddressBySessionId(sessionId))
            }

            /**
             * Assign a customer to a session.
             *
             * Description: Links a customer, and optionally one of their addresses, to the open session. Setting the delivery flag marks the session as a delivery order, which requires an address.
             *
             * Tag: table-sessions
             * Path: sessionId Table session identifier. Must be a positive number.
             * Response: 400 sessionId is not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No session with that id, or the customer or address does not exist.
             */
            post("/sessions/{sessionId}/assign-customer") {
                val sessionId =
                    call.parameters["sessionId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid session ID",
                        )
                if (sessionId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid session ID",
                    )
                }
                val request = call.receive<TableSessionDto.AssignCustomerAndAddressRequestDto>()
                logger.info("Assigning customer ${request.customerId} to session $sessionId (delivery: ${request.isDelivery})")
                val result = tableSessionService.assignCustomerAndAddress(sessionId, request)
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Remove the customer assignment from a session.
             *
             * Description: Unlinks the customer and address; the customer record itself is untouched. Use it when a ticket was attached to the wrong account.
             *
             * Tag: table-sessions
             * Path: sessionId Table session identifier. Must be a positive number.
             * Response: 400 sessionId is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No session with that id.
             */
            delete("/sessions/{sessionId}/remove-customer") {
                val sessionId =
                    call.parameters["sessionId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid session ID",
                        )
                if (sessionId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid session ID",
                    )
                }
                logger.info("Removing customer assignment from session $sessionId")
                val result = tableSessionService.removeCustomerAndAddress(sessionId)
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * List table sessions.
             *
             * Description: Optional query parameters narrow the result — `tableId`, `employeeId` as a UUID, `completed` as true or false, and `startDate` and `endDate` as ISO-8601 date-times. `limit` defaults to 50 and `offset` to 0. Unparseable filter values are ignored rather than rejected.
             *
             * Tag: table-sessions
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sessions") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()
                val tableId = call.request.queryParameters["tableId"]?.toLongOrNull()
                val employeeId =
                    call.request.queryParameters["employeeId"]?.let { param ->
                        runCatching { java.util.UUID.fromString(param) }.getOrNull()
                    }
                val completed = call.request.queryParameters["completed"]?.toBooleanStrictOrNull()
                val startDate =
                    call.request.queryParameters["startDate"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val endDate =
                    call.request.queryParameters["endDate"]
                        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

                logger.debug(
                    "Getting table sessions: limit=$limit offset=$offset tableId=$tableId " +
                        "employeeId=$employeeId completed=$completed startDate=$startDate endDate=$endDate",
                )
                val sessions =
                    tableSessionService.getAllSessions(
                        limit = limit ?: 50,
                        offset = offset ?: 0,
                        tableId = tableId,
                        employeeId = employeeId,
                        completed = completed,
                        startDate = startDate,
                        endDate = endDate,
                    ) ?: emptyList()
                call.respond(HttpStatusCode.OK, sessions)
            }

            /**
             * Get a table session by ID.
             *
             * Tag: table-sessions
             * Path: id Table session identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No session with that id.
             */
            get("/sessions/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid session ID",
                        )
                if (id <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid session ID",
                    )
                }
                val session =
                    tableSessionService.getSessionById(id)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Session $id not found",
                        )
                call.respond(HttpStatusCode.OK, session)
            }

            /**
             * Bulk-import tables from CSV.
             *
             * Description: Requires the capability RoutePermissions names for this route. Send the file as a multipart upload. The import is row-tolerant — rows whose table number already exists are skipped unless overwrite is set, and the response reports success and error counts. One bulk entry-log record is written for the run.
             *
             * Tag: tables
             * Path: overwrite Set true to update tables whose number already exists, false to skip them.
             * Response: 400 The request carried no CSV file part.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the capability this route requires.
             */
            post("/import-csv/{overwrite}") {
                val userId = call.getEmployeeIdFromJwt()
                val overwrite = call.parameters["overwrite"]?.toBooleanStrictOrNull() ?: false

                var csvBytes: ByteArray? = null
                var fileName: String? = null

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            csvBytes = part.provider().readRemaining().readByteArray()
                        }
                        else -> part.dispose()
                    }
                }

                val bytes =
                    csvBytes ?: throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "No CSV file provided",
                    )

                logger.info("Processing CSV import for tables: $fileName (${bytes.size} bytes) by user $userId, overwrite=$overwrite")

                val result = rTableService.importTablesCsv(bytes, overwrite)
                dbQuery {
                    EntryLogHelper.logOperation(
                        type = ChangeEntityType.RTABLE,
                        operation = LogOperation.CREATE,
                        entityId = "bulk-import",
                        entityName = "CSV Import: ${result.successCount} tables imported",
                        message = "Bulk imported ${result.successCount} tables from CSV (${result.errorCount} failed)",
                        userId = userId,
                    )
                }
                logger.info("CSV import completed: ${result.successCount} tables imported, ${result.errorCount} failed")
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Ask for the bill at a table.
             *
             * Description: Raises a billing request that appears on the cashiers' pending list — how a waiter signals from the floor that a table wants to pay. It settles nothing itself. Not under the order station's shared account: a bill is never registered under it, so asking for one is refused too — whatever a step-up lends. A person's own login asks for the bill from any device, the station's included.
             *
             * Tag: tables
             * Path: id Table identifier. Must be a positive number.
             * Response: 400 id is not a positive number.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 The request is attributed to the order station's shared account.
             * Response: 404 No table with that id.
             * Response: 409 The table has no active session.
             */
            post("/{id}/request-billing") {
                val tableId =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid table ID",
                        )
                if (tableId <= 0) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Invalid table ID",
                    )
                }
                val tokenClaims =
                    call.getTokenClaims()
                        ?: run {
                            call.respond(HttpStatusCode.Unauthorized, "Invalid authentication token")
                            return@post
                        }
                rTableService.requestBilling(tableId, tokenClaims.employeeId)
                call.respond(HttpStatusCode.OK)
            }

            /**
             * Repair tables stuck on a completed session.
             *
             * Description: Requires ADMIN role. Idempotent maintenance — clears the current-session pointer on any table that still references an already completed session, which otherwise leaves the table unusable. Safe to run repeatedly; the response reports how many tables were healed.
             *
             * Tag: table-sessions
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            post("/repair-completed-sessions") {
                logger.info("Running completed-but-current session repair")
                val result = tableSessionService.repairCompletedButCurrentSessions()
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Get tables changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the tables updated after the given timestamp.
             *
             * Tag: tables
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Last sync time is required",
                    )
                }
                val response = rTableService.getTablesUpdatedAfter(lastSyncTime)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
