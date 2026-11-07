/**
 * TSE Admin API routes for client-accessible TSE operations.
 *
 * Provides JWT-protected REST endpoints for TSE administration. Routes contain
 * no business logic — services and providers throw [PosException] on error,
 * and the global StatusPages plugin produces the application/problem+json
 * response.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.tse.data.provider.TseSdkAvailability
import org.example.project.features.tse.data.service.FilteredExportClientValidator
import org.example.project.features.tse.data.service.TseAdminUnlock
import org.example.project.features.tse.data.service.TseExportArchiveService
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.service.TseConfigurationService
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.features.tse.findings.TseFindingService
import org.example.project.features.tse.verification.TseExportDirectory
import org.example.project.features.tse.verification.TseExportSidecars
import org.example.project.features.tse.verification.TseVerificationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.formatter.getTimeZone
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

@Serializable
private data class PingResponse(
    val available: Boolean,
    /**
     * Whether the proprietary Swissbit SDK is on the server's classpath.
     *
     * Distinguishes "TSE hardware is offline" from "this server cannot drive a local
     * TSE at all", so the settings UI can disable LOCAL connection mode with an
     * actionable message instead of letting the operator pick a mode the server
     * rejects. Absent SDK also means terminals can never complete TSE client
     * registration, so NORMAL mode cannot sign anything.
     */
    val sdkAvailable: Boolean,
)

private val logger = LoggerFactory.getLogger("TseAdminRoutes")

fun Route.tseAdminRoutes() {
    val tseConfigService by inject<TseConfigurationService>()
    val tseAdminProvider by inject<TseAdminProvider>()
    val tseSignProvider by inject<TseSignProvider>()
    val tseVerificationService by inject<TseVerificationService>()
    val tseFindingService by inject<TseFindingService>()
    val transactionAuditService by inject<TransactionAuditService>()
    val tseExportDirectory by inject<TseExportDirectory>()
    val filteredExportClientValidator by inject<FilteredExportClientValidator>()
    val tseExportSidecars by inject<TseExportSidecars>()
    val tseExportArchiveService by inject<TseExportArchiveService>()

    authenticate("auth-jwt") {
        route("/api/tse") {
            // ========================================
            // Configuration Endpoints (Local Storage)
            // ========================================

            /**
             * Get the TSE configuration.
             *
             * Description: Requires ADMIN role. Returns the locally stored TSE settings — connection mode, endpoint and client identity. Credentials are never returned: `timeAdminPin` is always an empty string, and sending it back empty on `PUT /api/tse/config` keeps the stored PIN.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No TSE configuration has been stored yet.
             */
            get("/config") {
                logger.debug("GET /api/tse/config - Fetching TSE configuration")
                call.respond(HttpStatusCode.OK, tseConfigService.getTseConfig())
            }

            /**
             * Update the TSE configuration.
             *
             * Description: Requires ADMIN role. Writes the local TSE settings and refreshes the running provider, so the change takes effect without a restart. The calling device is taken from the JWT and recorded against the change. The echoed config never carries the TimeAdmin PIN. On a server without the Swissbit SDK the settings are still saved and the answer is 200 with applyStatus UNREACHABLE and a message naming the missing SDK.
             *
             * Tag: tse
             * Response: 400 The request body failed validation, or no TimeAdmin PIN would be stored (`TSE_TIME_ADMIN_PIN_REQUIRED` with a `timeAdminPin` field error) — every configuration needs one, in any mode.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not an ADMIN.
             */
            put("/config") {
                logger.debug("PUT /api/tse/config - Updating TSE configuration")

                val tokenClaims =
                    call.getTokenClaims() ?: throw PosException(
                        code = PosErrorCode.AUTH_FORBIDDEN,
                        httpStatus = HttpStatusCode.Unauthorized,
                        message = "Invalid or missing authentication token",
                    )

                val request = call.receive<TseConfigDto.UpdateTseConfigRequest>()
                val updated = tseConfigService.updateTseConfig(request, tokenClaims.deviceId)
                tseAdminProvider.refreshConfig()
                call.respond(HttpStatusCode.OK, updated)
            }

            // ========================================
            // Status & Info Endpoints (TSE Server)
            // ========================================

            /**
             * Get the TSE status.
             *
             * Description: Requires ADMIN role. Live device state — initialization, certificate expiry and remaining capacity.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/status") {
                logger.debug("GET /api/tse/status - Fetching TSE status")
                call.respond(HttpStatusCode.OK, tseAdminProvider.getStatus())
            }

            /**
             * Get TSE device information.
             *
             * Description: Requires ADMIN role. Identity of the device — serial number, firmware version and certificate details, as reported by the TSE itself.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/info") {
                logger.debug("GET /api/tse/info - Fetching TSE info")
                call.respond(HttpStatusCode.OK, tseAdminProvider.getInfo())
            }

            /**
             * Run a TSE health check.
             *
             * Description: Requires ADMIN role. Asks the device for its overall health verdict, which the settings screen shows as the TSE traffic light. `health` is derived the same way as for the signing path's health check — MAINTENANCE while a self test or time update is in flight (named in `maintenanceOps`); in NORMAL mode HEALTHY only when `healthy` is true and DEGRADED otherwise, so a non-healthy device never answers HEALTHY. After a TSE is re-plugged the server runs the self test and time update itself; expect MAINTENANCE for up to a minute.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/health") {
                logger.debug("GET /api/tse/health - Performing TSE health check")
                call.respond(HttpStatusCode.OK, tseAdminProvider.healthCheck())
            }

            /**
             * Get TSE flash memory health.
             *
             * Description: Requires ADMIN role. Wear and remaining-capacity figures for the device's flash storage — the early warning that a TSE is approaching end of life.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/flashhealth") {
                logger.debug("GET /api/tse/flashhealth - Fetching TSE flash health")
                call.respond(HttpStatusCode.OK, tseAdminProvider.flashHealth())
            }

            /**
             * Ping the TSE.
             *
             * Description: Requires ADMIN role. Reports two separate facts — whether the TSE currently answers, and whether the proprietary Swissbit SDK is on this server's classpath at all. The second distinguishes an offline device from a server that can never drive a local TSE, so the settings UI can disable LOCAL mode with an actionable message.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/ping") {
                logger.debug("GET /api/tse/ping - Pinging TSE server")
                val isAvailable = tseAdminProvider.ping()
                call.respond(
                    HttpStatusCode.OK,
                    PingResponse(
                        available = isAvailable,
                        sdkAvailable = TseSdkAvailability.isPresent(),
                    ),
                )
            }

            // ========================================
            // Admin Operation Endpoints (TSE Server)
            // ========================================

            /**
             * Run the TSE self-test.
             *
             * Description: Requires ADMIN role. Triggers the device's built-in self-test, which the TSE requires periodically before it will keep signing. Runs for the first active terminal registered on the TSE; a TSE 2.0 needs none.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 409 TSE 1.x with no active terminal registered on it (TSE_NOT_OPERATIONAL) — register a terminal's TSE client first. Nothing was sent to the device.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/selftest") {
                logger.info("POST /api/tse/selftest - Running TSE self-test")
                call.respond(HttpStatusCode.OK, tseAdminProvider.selfTest())
            }

            /**
             * Synchronise the TSE clock.
             *
             * Description: Requires ADMIN role. Pushes the server time to the device. The TSE refuses to sign once its clock has drifted too far, so this is part of routine maintenance.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/updatetime") {
                logger.info("POST /api/tse/updatetime - Syncing TSE time")
                call.respond(HttpStatusCode.OK, tseAdminProvider.updateTime())
            }

            /**
             * Export the whole TSE log.
             *
             * Description: Requires ADMIN role. Writes the complete TAR export to the configured export directory — the artefact handed to a tax auditor. Large TSEs make this a slow call.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/export/full") {
                logger.info("POST /api/tse/export/full - Full TSE export requested")
                call.respond(
                    HttpStatusCode.OK,
                    tseAdminProvider.exportFull(outputPath = tseExportDirectory.resolve().path),
                )
            }

            /**
             * Export the TSE log incrementally.
             *
             * Description: Requires ADMIN role. Exports only what changed since the state token returned by a previous export, optionally capped by a maximum size. Pass an output path to override the configured export directory.
             *
             * Tag: tse
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/export/incremental") {
                logger.info("POST /api/tse/export/incremental - Incremental TSE export requested")
                val request = call.receive<TseDto.IncrementalExportRequestDto>()
                val result =
                    tseAdminProvider.exportIncremental(
                        outputPath = request.outputPath ?: tseExportDirectory.resolve().path,
                        lastState = request.lastState,
                        maxExportSize = request.maxExportSize,
                    )
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Export the TSE log for a time range.
             *
             * Description: Requires ADMIN role. Exports only the entries between the given start and end dates, which is what an auditor asking for one period actually needs. Send either `startDay`/`endDay` (ISO `yyyy-MM-dd`) — resolved in the shop time zone as the whole of both days, the same inclusive period a DSFinV-K export uses — or `startDate`/`endDate` as inclusive Unix-millisecond instants. Optional `clientId` restricts the export to one TSE client (a terminal's client id, e.g. `TER-001`); absent or blank exports **all clients** of the TSE — the default, and what an auditor asking for a period needs. Needs no Admin PIN: exporting is permitted without an Admin login. The filter is recorded beside the TAR as `<file>.json` (client, range, request time, SHA-256), which `POST /api/tse/verify` reads to judge a single-client export as one.
             *
             * Tag: tse
             * Response: 400 Neither or both date forms were sent, a date is missing or malformed, the end precedes the start, or `clientId` is neither a terminal nor a client registered on the TSE.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 The TSE holds no log data in the requested range; no file is left behind.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/export/time-filtered") {
                logger.info("POST /api/tse/export/time-filtered - Time-filtered TSE export requested")
                val request = call.receive<TseDto.TimeFilteredExportRequestDto>()

                val (startMillis, endMillis) =
                    try {
                        request.resolveEpochMillis(getTimeZone())
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            code = PosErrorCode.VALIDATION_FAILED,
                            httpStatus = HttpStatusCode.BadRequest,
                            message = e.message ?: "Invalid parameters",
                            cause = e,
                        )
                    }

                val clientId = filteredExportClientValidator.resolve(request.clientId)
                val result =
                    tseAdminProvider.exportTimeFiltered(
                        outputPath = request.outputPath ?: tseExportDirectory.resolve().path,
                        startDate = startMillis,
                        endDate = endMillis,
                        clientId = clientId,
                    )
                tseExportSidecars.record(
                    result,
                    TseExportSidecars.Filter(clientId = clientId, startEpochMillis = startMillis, endEpochMillis = endMillis),
                )
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Export the TSE log for a transaction range.
             *
             * Description: Requires ADMIN role. Exports only the entries between the given start and end transaction numbers, for tracing one disputed receipt. Optional `clientId` restricts the export to one TSE client (a terminal's client id, e.g. `TER-001`); absent or blank exports **all clients** of the TSE, the default. The filter is recorded beside the TAR as `<file>.json` (client, range, request time, SHA-256), which `POST /api/tse/verify` reads to judge a single-client export as one.
             *
             * Tag: tse
             * Response: 400 The start or end transaction number is missing or malformed, or `clientId` is neither a terminal nor a client registered on the TSE.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 The TSE holds no log data in the requested range; no file is left behind.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/export/transaction-filtered") {
                logger.info("POST /api/tse/export/transaction-filtered - Transaction-filtered TSE export requested")
                val request = call.receive<TseDto.TransactionFilteredExportRequestDto>()

                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = e.message ?: "Invalid parameters",
                        cause = e,
                    )
                }

                val clientId = filteredExportClientValidator.resolve(request.clientId)
                val result =
                    tseAdminProvider.exportTransactionFiltered(
                        outputPath = request.outputPath ?: tseExportDirectory.resolve().path,
                        startTransaction = request.startTransaction,
                        endTransaction = request.endTransaction,
                        clientId = clientId,
                    )
                tseExportSidecars.record(
                    result,
                    TseExportSidecars.Filter(
                        clientId = clientId,
                        startTransaction = request.startTransaction,
                        endTransaction = request.endTransaction,
                    ),
                )
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Estimate the export size.
             *
             * Description: Requires ADMIN role. Reports how large an export would be, so an operator can tell whether the target medium has room before starting a slow full export. Pass the `lastState` query parameter to size an incremental export instead.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/export/size") {
                logger.info("GET /api/tse/export/size - Export size calculation requested")
                val lastState = call.request.queryParameters["lastState"]
                call.respond(HttpStatusCode.OK, tseAdminProvider.getExportSize(lastState))
            }

            /**
             * Export the TSE to an archive file, then delete it from the device.
             *
             * Description: Requires ADMIN role. One sitting on the TSE: Admin login, a full TAR export of everything the device holds (that login's own log entry included) written to the configured export directory, a completeness check of the written file, then the delete, then logout. The archive file is written FIRST and the device is erased only after the file is complete on disk. Nothing is deleted if any step fails; the error names the step. The response names the archive (`archiveFileName`, `archiveFilePath`, `archiveSizeBytes`) and the used storage before and after. The operator MUST keep that file for the statutory retention period: it is the only remaining copy of the deleted entries. The archive is entered in the TSE archive register (`archiveRegistered`), which the DSFinV-K export reads to ship it for a period reaching back into the deleted entries; if that fails after the erase, the reply still succeeds with `archiveRegistered: false` and the file must be registered with `POST /api/tse/archives`. Before the device is touched the server checks that the export directory is writable and has room for the archive.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 409 TSE_EXPORT_FAILED — the export directory is not writable; TSE_TIME_SYNC_REQUIRED — a TSE 2.0 without a valid time cannot delete. Nothing was exported or deleted.
             * Response: 422 The TSE refused a step (for example unfinished transactions). Nothing was deleted.
             * Response: 423 TSE administration is locked — unlock it with the Admin PIN first.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 TSE_EXPORT_FAILED — the archive could not be written completely; it was removed and nothing was deleted.
             * Response: 507 TSE_EXPORT_FAILED — the export directory has too little free space for the archive. Nothing was exported or deleted.
             */
            delete("/export") {
                logger.warn("DELETE /api/tse/export - Export-and-delete requested")
                val employeeId = call.getTokenClaims()?.employeeId
                call.respond(HttpStatusCode.OK, tseExportArchiveService.exportAndDelete(employeeId))
            }

            /**
             * Register an existing TSE archive.
             *
             * Description: Requires the TSE administration capability. Enters a TAR that is already in the TSE export folder — named by `fileName`, never by path — in the TSE archive register: typically an export-and-delete archive written before the register existed, or one restored from a backup. The server parses it, records its device serial, TANR, signature-counter and log-time ranges, size and SHA-256, and the DSFinV-K export then ships it for a period whose entries are no longer on the TSE, after re-checking the SHA-256. Does not touch the TSE.
             *
             * Tag: tse
             * Response: 201 The archive was registered; the body describes what it holds.
             * Response: 400 The file name is missing or resolves outside the export folder.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the TSE administration capability.
             * Response: 404 No file with that name in the export folder.
             * Response: 409 TSE_ARCHIVE_ALREADY_REGISTERED — a file with the same content is already registered.
             * Response: 422 The file holds no TSE log message, or log messages of more than one TSE.
             */
            post("/archives") {
                val request = call.receive<TseDto.RegisterExportArchiveRequestDto>()
                if (request.fileName.isBlank()) {
                    throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "fileName is required")
                }
                logger.info("POST /api/tse/archives - registering archive ${request.fileName}")
                val employeeId = call.getTokenClaims()?.employeeId
                call.respond(HttpStatusCode.Created, tseExportArchiveService.register(request.fileName, employeeId))
            }

            // ========================================
            // Log-Sequence Verification Endpoint (AGD §5.2 plausibility checks)
            // ========================================

            /**
             * Verify an exported TSE log.
             *
             * Description: Requires ADMIN role. Runs the AGD §5.2 plausibility checks over a previously written export file — signature counters and log sequence — and compares it with the POS's records both ways. A filtered export is judged against the filter recorded beside it (`<file>.json`): for a single-client export the TANR and signature-counter holes other clients leave are expected, and completeness is checked against that client's POS transactions over the requested range; an INFO finding `EXPORT_FILTER_APPLIED` names the filter. Without a matching record the file is judged as an unfiltered export.
             *
             * Tag: tse
             * Response: 400 The file name is missing.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No export file with that name in the export directory.
             */
            post("/verify") {
                val request = call.receive<TseVerificationDto.VerifyRequestDto>()
                logger.info("POST /api/tse/verify - verifying export ${request.fileName}")
                call.respond(HttpStatusCode.OK, tseVerificationService.verifyExport(request.fileName))
            }

            // ========================================
            // TANR findings and their append-only explanations (AEAO § 146a Nr. 1.15.1)
            // ========================================

            /**
             * List TANR findings and their explanations.
             *
             * Description: Requires the TSE administration capability. Lists the TSE transaction numbers the POS has no ordinary record of (gaps in a device's sequence, and the same number recorded twice), for the given dates or the whole history, each with the explanation that currently covers it.
             *
             * Tag: tse
             * Response: 400 A date parameter is not an ISO date.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the TSE administration capability.
             */
            get("/findings") {
                val from = call.request.queryParameters["from"]?.let { parseFindingDate(it, "from") }
                val to = call.request.queryParameters["to"]?.let { parseFindingDate(it, "to") }
                call.respond(HttpStatusCode.OK, tseFindingService.findings(from, to))
            }

            /**
             * Add an explanation for a TANR range.
             *
             * Description: Requires the TSE administration capability. Records why a range of TSE transaction numbers has no ordinary POS record. Explanations are never changed or deleted; a correction is a new entry that names the one it replaces.
             *
             * Tag: tse
             * Response: 400 The range is inverted, or the text is blank or too long.
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller lacks the TSE administration capability.
             */
            post("/explanations") {
                val tokenClaim =
                    call.getTokenClaims() ?: throw PosException(
                        code = PosErrorCode.AUTH_FORBIDDEN,
                        httpStatus = HttpStatusCode.Unauthorized,
                        message = "Invalid or missing authentication token",
                    )
                val request = call.receive<TseExplanationDto.CreateRequestDto>()
                val created = tseFindingService.explain(request, tokenClaim.employeeId)
                transactionAuditService.logTransaction(
                    employeeId = tokenClaim.employeeId,
                    employeeNumber = tokenClaim.employeeNumber,
                    authorizationType = "DIRECT",
                    authorizingManagerId = tokenClaim.employeeId,
                    operation = "TSE_EXPLANATION_CREATED",
                    entityType = "TSE_EXPLANATION",
                    entityId = created.id.toString(),
                    entityName = "TANR ${created.tanrFrom}-${created.tanrTo}",
                    reason = created.text.take(500),
                    metadata = mapOf("tseSerialHex" to created.tseSerialHex, "category" to created.category.name),
                )
                call.respond(HttpStatusCode.Created, created)
            }

            // ========================================
            // Admin session — holds the operator-supplied Admin PIN in memory only
            // ========================================

            /**
             * Unlock TSE administration.
             *
             * Description: Requires ADMIN role. Takes the operator-supplied Admin PIN, checks it with one read-only admin call on the device, and holds it in memory only for the session — it is never written to disk or to the database. A PIN the card refuses leaves administration locked. When the device cannot be asked (not commissioned, unreachable), the PIN cannot be checked and the sitting is kept. Maintenance operations refuse with 423 until this has been done, and the session expires after the idle timeout reported in the response.
             *
             * Tag: tse
             * Response: 400 The PIN is missing or malformed.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 422 The TSE rejected the Admin PIN, or the Admin PIN is blocked; administration stays locked.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             */
            post("/admin/unlock") {
                logger.info("POST /api/tse/admin/unlock - TSE administration unlock requested")
                val request = call.receive<TseDto.AdminPinRequestDto>()
                request.validate()
                TseAdminUnlock(tseAdminProvider).unlock(request.pass)
                call.respond(
                    HttpStatusCode.OK,
                    TseDto.TseAdminSessionStatusDto(
                        unlocked = true,
                        idleTimeoutSeconds = TseAdminSession.IDLE_TIMEOUT_MS / 1000,
                    ),
                )
            }

            /**
             * Lock TSE administration.
             *
             * Description: Requires ADMIN role. Clears the in-memory Admin PIN and logs the Admin user out on the device itself — clearing the PIN alone would leave Admin signed in on the TSE. The device logout is best-effort and its failure does not fail the lock.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            post("/admin/lock") {
                logger.info("POST /api/tse/admin/lock - TSE administration locked")
                TseAdminSession.clear()
                // End the device-side sitting too, immediately. Clearing the in-memory PIN
                // alone would leave Admin logged in on the TSE — permanently on 1.x, and on
                // 2.0 for as long as maintenance keeps reusing it. Best-effort: a failed
                // logout must not fail the lock.
                runCatching { tseAdminProvider.logoutAdmin() }
                    .onFailure { logger.warn("Admin device logout on lock failed: ${it.message}") }
                call.respond(
                    HttpStatusCode.OK,
                    TseDto.TseAdminSessionStatusDto(
                        unlocked = false,
                        idleTimeoutSeconds = TseAdminSession.IDLE_TIMEOUT_MS / 1000,
                    ),
                )
            }

            /**
             * Get the TSE administration lock state.
             *
             * Description: Requires ADMIN role. Reports whether an Admin PIN is currently held and how long the session may idle before it expires. Touches no hardware, and reading it does not extend the idle window — only an administrative action that uses the PIN does.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/admin/status") {
                call.respond(
                    HttpStatusCode.OK,
                    TseDto.TseAdminSessionStatusDto(
                        unlocked = TseAdminSession.isUnlocked(),
                        idleTimeoutSeconds = TseAdminSession.IDLE_TIMEOUT_MS / 1000,
                    ),
                )
            }

            /**
             * Commission the TSE.
             *
             * Description: Requires ADMIN role. Runs the certified initialization sequence with the operator-supplied credentials and registers one terminal as the TSE's first client: the one named by the optional `terminalId` (its client id, e.g. `TER-001`), else the calling device's own terminal. A chosen terminal must be active and fiscal but need not be paired to a device, so the back office (a browser, not a terminal) can commission the TSE. On success that terminal is flagged TSE-registered, as `tse-register` would. The TSE is always commissioned in NORMAL mode: a shop configured DISABLED (the fresh-install value) is commissioned and its configured mode saved as NORMAL, so the operator need not switch the mode first; a failed setup leaves the configured mode as it was. One-time per device — a commissioned TSE cannot be set up again without a factory reset.
             *
             * Tag: tse
             * Response: 400 The supplied credentials failed validation, the `terminalId` is blank or holds characters a TSE rejects, no TimeAdmin PIN is configured (`TSE_TIME_ADMIN_PIN_REQUIRED`), or the Admin PIN, PUK or the configured TimeAdmin PIN is still a factory placeholder (named in the message; nothing is written and the TSE mode is left as it was).
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 404 `TERMINAL_NOT_FOUND`: no terminal has the chosen `terminalId`. `TSE_SETUP_TERMINAL_REQUIRED`: no `terminalId` was given and the calling device is not a terminal — choose one, or create one first. Nothing is written to the TSE.
             * Response: 409 The TSE is already commissioned (`TSE_ALREADY_INITIALIZED`); nothing was written and the credentials given were not applied. Or the terminal cannot become a TSE client: `TERMINAL_DEACTIVATED`, `TERMINAL_NOT_FISCAL` — refused before the TSE is touched.
             * Response: 409 `TSE_INVALID_PARAMETERS`: the TSE is configured for SIMULATION mode, which setup does not end on its own — switch the mode to NORMAL or DISABLED first. Refused before the TSE is touched.
             * Response: 503 `TSE_HARDWARE_NOT_FOUND`: no TSE was found to commission. Nothing is written.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/setup") {
                logger.info("POST /api/tse/setup - TSE setup/initialization requested")
                val tokenClaim =
                    call.getTokenClaims() ?: throw PosException(
                        code = PosErrorCode.AUTH_FORBIDDEN,
                        httpStatus = HttpStatusCode.Unauthorized,
                        message = "Invalid or missing authentication token",
                    )
                val credentials = call.receive<TseDto.SetupCredentialsRequestDto>()
                credentials.validate()
                call.respond(
                    HttpStatusCode.OK,
                    tseAdminProvider.setup(tokenClaim.deviceId, credentials),
                )
            }

            /**
             * Factory-reset the TSE.
             *
             * Description: Requires ADMIN role. Destructive — it erases the device's state and stored log entries and returns it to its uncommissioned condition. Needs no Admin PIN and no unlocked TSE administration, because the device accepts it only on development firmware; a production TSE refuses it. Only ever appropriate on a development or replacement TSE, never on one holding unexported fiscal data.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/factory-reset") {
                logger.warn("POST /api/tse/factory-reset - Factory reset requested (DESTRUCTIVE OPERATION)")
                call.respond(HttpStatusCode.OK, tseAdminProvider.factoryReset())
            }

            // ========================================
            // Restart Endpoint
            // ========================================

            /**
             * Restart the TSE hardware connection.
             *
             * Description: Requires ADMIN role. Closes and re-initializes this server's in-process connection to the local TSE, waits about ten seconds, then resets the signing mode to NORMAL, resumes the signing retry queue and runs a health check. A failed re-initialization is logged and the health check still runs. Answers 200 whenever the sequence completes — the body's success flag says whether the TSE is healthy again, since a device that is still initializing is not an error.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 500 The restart sequence itself failed.
             */
            post("/restart") {
                logger.info("POST /api/tse/restart - Restarting TSE Connector Server")

                try {
                    // Step 1: Tell TSE Connector Server to restart.
                    // Server might already be down — swallow PosException and continue.
                    try {
                        tseAdminProvider.restartServer()
                    } catch (e: PosException) {
                        logger.warn("TSE server restart command failed (server may be down): ${e.message}")
                    }

                    // Step 2: Wait for TSE server to restart (context close + re-init)
                    delay(10000L)

                    // Step 3: Re-establish connection from POS side
                    val healthResult = tseSignProvider.restartConnection()
                    if (healthResult != null && healthResult.healthy) {
                        call.respond(
                            HttpStatusCode.OK,
                            TseDto.AdminResultDto(
                                success = true,
                                message = "TSE server restarted and connection re-established",
                            ),
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.OK,
                            TseDto.AdminResultDto(
                                success = false,
                                message =
                                    "TSE server restart initiated but connection not yet healthy. " +
                                        "It may still be initializing.",
                            ),
                        )
                    }
                } catch (e: Exception) {
                    logger.error("TSE restart failed: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        TseDto.AdminResultDto(
                            success = false,
                            message = "TSE restart failed: ${e.message}",
                        ),
                    )
                }
            }

            // ========================================
            // Decommission Endpoints
            // ========================================

            /**
             * Decommission the TSE.
             *
             * Description: Requires ADMIN role. Irreversible — it permanently disables the device, as required when a till is retired. The TSE can never sign again afterwards, so export and archive everything first.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 423 TSE administration is locked — unlock it with the Admin PIN first.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/decommission") {
                logger.warn("POST /api/tse/decommission - IRREVERSIBLE OPERATION requested")
                call.respond(HttpStatusCode.OK, tseAdminProvider.decommission())
            }

            // ========================================
            // CSP Export Endpoints
            // ========================================

            /**
             * Disable export if the CSP self-test fails.
             *
             * Description: Requires ADMIN role. Runs the crypto service provider test and, if it fails, disables export on the device — the containment step the TSE specification prescribes for a device whose signing subsystem is suspect.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 423 TSE administration is locked — unlock it with the Admin PIN first.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/csp-export/disable") {
                logger.info("POST /api/tse/csp-export/disable - Disabling export if CSP test fails")
                call.respond(HttpStatusCode.OK, tseAdminProvider.disableExportIfCspTestFails())
            }

            // ========================================
            // Log Entry Browsing Endpoints
            // ========================================

            /**
             * Browse TSE log entries.
             *
             * Description: Requires ADMIN role. Reads a window of raw log entries straight off the device for inspection, without producing an export file.
             *
             * Tag: tse
             * Response: 400 The browse parameters failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/entries/browse") {
                logger.debug("POST /api/tse/entries/browse - Browsing log entries")
                val request = call.receive<TseDto.LogEntryBrowseRequestDto>()

                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw PosException(
                        code = PosErrorCode.VALIDATION_FAILED,
                        httpStatus = HttpStatusCode.BadRequest,
                        message = e.message ?: "Invalid parameters",
                        cause = e,
                    )
                }

                call.respond(HttpStatusCode.OK, tseAdminProvider.browseEntries(request))
            }

            // ========================================
            // LCM Export Endpoints
            // ========================================

            /**
             * Export the TSE life-cycle log.
             *
             * Description: Requires ADMIN role. Exports the LCM data — the device's own record of commissioning, firmware and key events, separate from the transaction log.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/export/lcm") {
                logger.info("POST /api/tse/export/lcm - LCM export requested")
                call.respond(HttpStatusCode.OK, tseAdminProvider.exportLcm())
            }

            // ========================================
            // Firmware Endpoints
            // ========================================

            /**
             * Check for a TSE firmware update.
             *
             * Description: Requires ADMIN role. Compares the device's firmware against the bundled one and reports whether an update is available.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/firmware-check") {
                logger.info("GET /api/tse/firmware-check - Checking firmware update")
                call.respond(HttpStatusCode.OK, tseAdminProvider.checkFirmwareUpdate())
            }

            /**
             * Apply the bundled TSE firmware update.
             *
             * Description: Requires ADMIN role. Flashes the firmware shipped with this server onto the device. The TSE is unavailable for signing while it runs, so it belongs outside trading hours.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 423 TSE administration is locked — unlock it with the Admin PIN first.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            post("/firmware-apply-bundled") {
                logger.warn("POST /api/tse/firmware-apply-bundled - Applying bundled firmware update")
                call.respond(HttpStatusCode.OK, tseAdminProvider.applyBundledFirmwareUpdate())
            }

            // ========================================
            // Client & Transaction List Endpoints
            // ========================================

            /**
             * List registered TSE clients.
             *
             * Description: Requires ADMIN role. The client IDs registered on the device — one per till that may sign against this TSE.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 423 TSE administration is locked — unlock it with the Admin PIN first.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/clients") {
                logger.debug("GET /api/tse/clients - Listing registered clients")
                call.respond(HttpStatusCode.OK, tseAdminProvider.listClients())
            }

            /**
             * List open TSE transactions.
             *
             * Description: Requires ADMIN role. Transactions started on the device and not yet finished. A non-empty list after trading has stopped points at receipts that never completed.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/transactions") {
                logger.debug("GET /api/tse/transactions - Listing open transactions")
                call.respond(HttpStatusCode.OK, tseAdminProvider.listTransactions())
            }

            /**
             * Get the last TSE transaction.
             *
             * Description: Requires ADMIN role. The most recent transaction the device signed, used to reconcile the till against the TSE log.
             *
             * Tag: tse
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 424 The Swissbit SDK is not installed on this server, so no local TSE can be driven.
             * Response: 500 The TSE rejected the operation or the device could not be reached.
             */
            get("/last-transaction") {
                logger.debug("GET /api/tse/last-transaction - Getting last transaction")
                call.respond(HttpStatusCode.OK, tseAdminProvider.lastTransaction())
            }
        }
    }
}

/**
 * An ISO date from the query string, or a 400 — a malformed date must not escape as a 500 out of
 * kotlinx-datetime's own parse failure.
 */
private fun parseFindingDate(
    raw: String,
    parameter: String,
): LocalDate =
    try {
        LocalDate.parse(raw)
    } catch (e: IllegalArgumentException) {
        throw PosException(
            code = PosErrorCode.VALIDATION_FAILED,
            httpStatus = HttpStatusCode.BadRequest,
            message = "$parameter must be an ISO date (yyyy-MM-dd)",
            cause = e,
        )
    }
