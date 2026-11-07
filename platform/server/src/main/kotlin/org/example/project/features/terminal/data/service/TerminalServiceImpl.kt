/**
 * Implementation of TerminalService for terminal business logic.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 11.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.isTseSafeClientId
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.terminal.domain.mapper.toResponseDto
import org.example.project.features.terminal.domain.mapper.toSummaryDto
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.models.TerminalPairingCode
import org.example.project.features.terminal.domain.repository.TerminalPairingCodeRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.service.TerminalFiscalGateway
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.getTimeZone
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime

/**
 * Implementation of TerminalService using repository pattern.
 *
 * Provides business logic for terminal registration and management
 * with proper transaction boundaries controlled at service level.
 *
 * @param repository Terminal repository for data access
 * @param fiscalGateway The TSE device, the TSE admin sitting, the ecr signing state and the
 *   DSFinV-K Stammdaten freeze, called at exactly the points (and in the transactions) where this
 *   service called them inline before the gateway was extracted
 */
class TerminalServiceImpl(
    private val repository: TerminalRepository,
    private val pairingCodeRepository: TerminalPairingCodeRepository,
    private val fiscalGateway: TerminalFiscalGateway,
    private val configManager: IShopConfigurationManager,
    private val capabilityResolver: TerminalCapabilityResolver,
    private val browserStationPolicy: BrowserStationPolicy,
) : TerminalService {
    private val logger = LoggerFactory.getLogger(TerminalServiceImpl::class.java)

    private val pairingCodeTtlMinutes = 10

    /** Cap on regeneration attempts when a freshly minted code collides with another live one. */
    private val maxCodeGenerationAttempts = 20

    /** `terminal_sw_version` is VARCHAR(50), matching DSFinV-K's Alpha(50) TERMINAL_SW_VERSION. */
    private val swVersionMaxLength = 50

    override suspend fun createTerminal(
        employeeId: String,
        request: TerminalDto.CreateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto {
        // Refused, not defaulted: a typo used to provision a fiscal CASHIER till (QA 2026-10-06). An
        // absent role still means CASHIER — the DTO default, which older clients rely on.
        val role =
            TerminalRole.parseOrNull(request.role)
                ?: throw PosException(
                    code = PosErrorCode.VALIDATION_FAILED,
                    httpStatus = HttpStatusCode.BadRequest,
                    message = "Unknown terminal role: ${request.role}",
                    fields = listOf(FieldError(field = "role", code = "UNKNOWN")),
                )
        return ExceptionConverter.catchingThrows(
            context = "createTerminal(name=${request.name}, role=${request.role}, employeeId=$employeeId)",
            employeeId = employeeId,
        ) {
            val fiscal = request.fiscal ?: role.defaultFiscal
            dbQuery {
                val created = repository.createUnbound(request.name, role.name, fiscal)
                created.toResponseDto()
            }
        }
    }

    /**
     * Without [registerTseClient] the code pairs without touching the TSE and is bound to no admin
     * sitting. The sitting binding a fiscal terminal's code used to get unconditionally existed only
     * because pairing was the moment its client would be registered; a code that registers nothing
     * has no sitting to outlive, so that binding was dropped with the opt-in (F42).
     *
     * With it, the TSE must be able to take the registration now (fiscal terminal, NORMAL mode,
     * commissioned — else 409), and a client the TSE does not list needs the admin sitting unlocked
     * (else 423) and binds the code to it. The client list itself needs the Admin login; when it
     * cannot be read (sitting locked) the database flag stands in for it.
     */
    @OptIn(ExperimentalTime::class) // toInstant() returns kotlin.time.Instant — see TerminalService plan Task 5 note
    override suspend fun generatePairingCode(
        employeeId: String,
        terminalDbId: Long,
        registerTseClient: Boolean,
    ): TerminalDto.PairingCodeResponseDto =
        ExceptionConverter.catchingThrows(
            context = "generatePairingCode(terminalDbId=$terminalDbId, registerTseClient=$registerTseClient, employeeId=$employeeId)",
            employeeId = employeeId,
        ) {
            val terminal = dbQuery { findTerminalForCode(terminalDbId) }
            // TSE calls happen here, outside any transaction.
            var listedOnTse: Boolean? = null
            if (registerTseClient) {
                requireTseCanRegister(terminal)
                listedOnTse = readRegisteredClientIdsOrNull()?.contains(terminal.clientId)
            }
            val needsSitting = registerTseClient && !(listedOnTse ?: terminal.tseClientRegistered)
            if (needsSitting && !fiscalGateway.isAdminUnlocked()) throw adminLockedForPairing(terminal.clientId)

            var deviceIdToInvalidate: String? = null
            val response =
                dbQuery {
                    val current = findTerminalForCode(terminalDbId)
                    val epoch =
                        if (needsSitting) {
                            fiscalGateway.currentAdminEpochIfUnlocked() ?: throw adminLockedForPairing(current.clientId)
                        } else {
                            null
                        }
                    // The device just confirmed the client: record it now, so a pairing that later
                    // finds the sitting locked (and the list unreadable) does not mistake it for new.
                    if (listedOnTse == true && !current.tseClientRegistered) {
                        current.tseClientRegistered = true
                        deviceIdToInvalidate = current.deviceId
                    }
                    mintPairingCode(current, epoch, registerTseClient)
                }
            // AFTER commit — see pairDevice for why invalidating pre-commit races.
            capabilityResolver.invalidate(deviceIdToInvalidate)
            response
        }

    override suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto {
        val mode = configManager.getTseMode()
        // A disabled TSE is not asked anything; isInitialized() itself never throws a PosException.
        val initialized = mode != TseMode.DISABLED && fiscalGateway.isTseInitialized()
        return TerminalDto.TsePairingStatusDto(
            mode = mode,
            initialized = initialized,
            available = mode == TseMode.NORMAL && initialized,
        )
    }

    private fun findTerminalForCode(terminalDbId: Long): Terminal =
        repository.findById(terminalDbId)
            ?: throw PosException(PosErrorCode.TERMINAL_NOT_FOUND, HttpStatusCode.NotFound, "Terminal $terminalDbId not found")

    /** Invalidates any outstanding code for [terminal] and mints a fresh, globally-unique one. Runs inside the caller's dbQuery. */
    @OptIn(ExperimentalTime::class)
    private fun mintPairingCode(
        terminal: Terminal,
        epoch: Long?,
        registerTseClient: Boolean,
    ): TerminalDto.PairingCodeResponseDto {
        val now = currentTimeStamp()
        pairingCodeRepository.consumeAllForTerminal(terminal, now)

        // 6-digit codes carry no DB-level uniqueness constraint; loop until the candidate isn't
        // currently live for ANY terminal, so pairDevice's code -> terminal resolution stays
        // unambiguous even with two pairing codes in flight at once.
        var code = PairingCodeGenerator.newCode()
        var attempts = 1
        while (pairingCodeRepository.isCodeLive(code, now)) {
            if (attempts >= maxCodeGenerationAttempts) {
                throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Could not allocate a globally-unique pairing code after $attempts attempts",
                )
            }
            code = PairingCodeGenerator.newCode()
            attempts++
        }
        val expiresAt =
            now
                .toInstant(getTimeZone())
                .plus(DateTimePeriod(minutes = pairingCodeTtlMinutes), getTimeZone())
                .toLocalDateTime(getTimeZone())
        pairingCodeRepository.create(terminal, code, expiresAt, epoch, registerTseClient)
        return TerminalDto.PairingCodeResponseDto(
            terminalDbId = terminal.id.value,
            terminalId = terminal.clientId,
            code = code,
            expiresAt = expiresAt.toString(),
            rootFingerprint = TlsRootCa.current()?.fingerprint,
        )
    }

    /**
     * Pairs in three steps so no transaction is held open across the TSE: validate the code
     * (consuming nothing), register the TSE client when the code asks for it, then re-validate the
     * code and bind inside one transaction. A failed registration therefore binds nothing and leaves
     * the code usable. A code issued without `registerTseClient` makes no TSE call at all.
     */
    @OptIn(ExperimentalTime::class) // toInstant() returns kotlin.time.Instant — see TerminalService plan Task 5 note
    override suspend fun pairDevice(
        request: TerminalDto.PairDeviceRequestDto,
    ): TerminalDto.PairDeviceResponseDto =
        ExceptionConverter.catchingThrows(
            context = "pairDevice(deviceId=${request.deviceId})",
            deviceId = request.deviceId,
        ) {
            val token = DeviceTokenGenerator.newToken()
            val tokenHash = DeviceTokenGenerator.hash(token)

            val (validatedTerminal, registerTse) =
                dbQuery {
                    val pairing = requireRedeemablePairing(request.code)
                    pairing.terminal to pairing.registerTseClient
                }
            // Refused BEFORE any TSE registration or binding: a refusal consumes nothing (the code stays
            // redeemable once the role is enabled) and, being neither PAIRING_CODE_INVALID nor _EXPIRED,
            // does not count against PairAttemptLimiter.
            browserStationPolicy.requireAllowed(
                ClientKind.effective(request.clientKind, request.deviceId),
                TerminalRole.fromCode(validatedTerminal.role),
            )
            if (registerTse) ensureTseClientForPairing(validatedTerminal)

            // Captured INSIDE the mutating dbQuery below (must be, to read the pre-mutation terminal
            // state), but only ACTED on after that transaction has committed — see the invalidate()
            // calls after the dbQuery block for why.
            var previousDeviceIdOnTerminal: String? = null
            var movedFromDeviceId: String? = null

            val responseDto =
                dbQuery {
                    // Re-validated: the code may have been redeemed, expired or lost its sitting
                    // while the TSE was being asked.
                    val pairing = requireRedeemablePairing(request.code)
                    val terminal = pairing.terminal
                    if (terminal.id != validatedTerminal.id) {
                        throw PosException(PosErrorCode.PAIRING_CODE_INVALID, HttpStatusCode.BadRequest, "Invalid pairing code")
                    }
                    // Capture BEFORE bindDevice overwrites it — if this terminal already had a different
                    // device bound (rebind), that device's cached capability is now stale too.
                    previousDeviceIdOnTerminal = terminal.deviceId
                    // The device may currently be bound to a DIFFERENT terminal (moved between stations).
                    // device_id is unique, so clear the old binding first — otherwise bindDevice below
                    // hits the unique constraint and surfaces as a raw 500 instead of a clean rebind.
                    val currentOwner = repository.findByDeviceId(request.deviceId)
                    if (currentOwner != null && currentOwner.id != terminal.id) {
                        movedFromDeviceId = currentOwner.deviceId
                        repository.clearBinding(currentOwner)
                    }
                    // Device token expiry: same computation as the pairing-code TTL, but a much longer
                    // window — this only time-bounds a leaked token; re-pairing rotates it.
                    val tokenExpiresAt =
                        currentTimeStamp()
                            .toInstant(getTimeZone())
                            .plus(DateTimePeriod(days = 90), getTimeZone())
                            .toLocalDateTime(getTimeZone())
                    repository.bindDevice(
                        terminal = terminal,
                        deviceId = request.deviceId,
                        model = request.model,
                        brand = request.brand,
                        serialNr = request.serialNr,
                        swVersion = request.swVersion,
                        deviceTokenHash = tokenHash,
                        tokenExpiresAt = tokenExpiresAt,
                        clientKind = ClientKind.effective(request.clientKind, request.deviceId),
                    )
                    // Same flag write as registerTerminalTse; the device ids it affects are
                    // invalidated below with the binding's.
                    if (pairing.registerTseClient) terminal.tseClientRegistered = true
                    pairingCodeRepository.markConsumed(pairing, currentTimeStamp())
                    terminal.toResponseDto()
                }
            // Invalidate every deviceId touched by this pairing — AFTER the transaction above has
            // committed: the newly-bound device, the terminal's previous device (rebind), and the old
            // owner's device (device-move). Invalidating from INSIDE the dbQuery (pre-commit) would let
            // a concurrent request that cache-misses in that window read the still-uncommitted (stale)
            // row under READ_COMMITTED and re-cache it — with no later invalidate to correct it.
            capabilityResolver.invalidate(request.deviceId)
            capabilityResolver.invalidate(previousDeviceIdOnTerminal)
            capabilityResolver.invalidate(movedFromDeviceId)
            TerminalDto.PairDeviceResponseDto(terminal = responseDto, deviceToken = token)
        }

    /** The unconsumed, unexpired code for [code], still inside its sitting, for an active terminal. Runs inside a dbQuery. */
    private fun requireRedeemablePairing(code: String): TerminalPairingCode {
        val pairing =
            pairingCodeRepository.findUnconsumedByCode(code)
                ?: throw PosException(PosErrorCode.PAIRING_CODE_INVALID, HttpStatusCode.BadRequest, "Invalid pairing code")
        if (pairing.expiresAt < currentTimeStamp()) {
            throw PosException(PosErrorCode.PAIRING_CODE_EXPIRED, HttpStatusCode.BadRequest, "Pairing code expired")
        }
        // Sitting-scoped codes (registration on pairing): the issuing sitting must still be the live one.
        val boundEpoch = pairing.adminSessionEpoch
        if (boundEpoch != null && fiscalGateway.currentAdminEpochIfUnlocked() != boundEpoch) {
            throw PosException(
                PosErrorCode.PAIRING_CODE_EXPIRED,
                HttpStatusCode.BadRequest,
                "Pairing code's admin sitting ended",
            )
        }
        if (!pairing.terminal.isActive) {
            throw PosException(PosErrorCode.TERMINAL_NEEDS_PAIRING, HttpStatusCode.Conflict, "Terminal is deactivated")
        }
        return pairing
    }

    /**
     * Makes sure the TSE knows [terminal]'s client before the device is bound. Listed → nothing to
     * do. Not listed → register (under the sitting the code was bound to). List unreadable — the
     * sitting is locked, so the code was issued for a client the database already records as
     * registered — → the flag stands. Every refusal becomes [PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED].
     */
    private suspend fun ensureTseClientForPairing(terminal: Terminal) {
        try {
            requireTseCanRegister(terminal)
        } catch (e: PosException) {
            throw pairingTseRegistrationFailed(terminal.clientId, e.message, e)
        }
        val listed = readRegisteredClientIdsOrNull()?.contains(terminal.clientId)
        if (listed == true) {
            logger.info("Pairing: TSE already lists ${terminal.clientId}; no registration needed")
            return
        }
        if (listed == null && terminal.tseClientRegistered) {
            logger.warn("Pairing: TSE client list unreadable; keeping ${terminal.clientId}'s recorded registration")
            return
        }
        if (!isTseSafeClientId(terminal.clientId)) {
            throw pairingTseRegistrationFailed(terminal.clientId, "clientId contains characters a TSE 2.0 rejects", null)
        }
        logger.info("Pairing: registering ${terminal.clientId} with the TSE")
        val result =
            try {
                fiscalGateway.registerClient(terminal.clientId)
            } catch (e: PosException) {
                throw pairingTseRegistrationFailed(terminal.clientId, e.message, e)
            }
        if (!result.success) throw pairingTseRegistrationFailed(terminal.clientId, result.message, null)
    }

    /** 409 unless the TSE can take a client registration now: fiscal terminal, NORMAL mode, commissioned. */
    private suspend fun requireTseCanRegister(terminal: Terminal) {
        if (!terminal.fiscal) {
            throw PosException(
                PosErrorCode.TERMINAL_NOT_FISCAL,
                HttpStatusCode.Conflict,
                "Terminal ${terminal.clientId} is not fiscal and has no TSE client to register",
            )
        }
        val mode = configManager.getTseMode()
        if (mode == TseMode.DISABLED) throw tseDisabled()
        if (mode != TseMode.NORMAL || !fiscalGateway.isTseInitialized()) {
            throw PosException(
                PosErrorCode.TSE_NOT_OPERATIONAL,
                HttpStatusCode.Conflict,
                "The TSE is not in NORMAL mode and commissioned; pair without registering at the TSE",
            )
        }
    }

    /** The client ids the TSE lists, or null when the list cannot be read (e.g. the Admin sitting is locked). */
    private suspend fun readRegisteredClientIdsOrNull(): Set<String>? =
        try {
            fetchRegisteredClientIds()
        } catch (e: PosException) {
            logger.info("TSE client list unreadable (${e.code}); falling back to the database flag")
            null
        }

    private fun adminLockedForPairing(clientId: String) =
        PosException(
            PosErrorCode.TSE_ADMIN_LOCKED,
            // 423, as LocalTseAdminProvider answers it: the back office prompts for the PIN and retries.
            HttpStatusCode.Locked,
            "Registering $clientId at the TSE while pairing needs the TSE admin sitting unlocked",
        )

    private fun pairingTseRegistrationFailed(
        clientId: String,
        reason: String?,
        cause: Throwable?,
    ) = PosException(
        code = PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED,
        httpStatus = HttpStatusCode.FailedDependency,
        message = "Could not register $clientId at the TSE; the device was not paired and the code is still valid: $reason",
        cause = cause,
    )

    override suspend fun revokeBinding(
        employeeId: String,
        terminalDbId: Long,
    ): TerminalDto.TerminalSummaryDto =
        ExceptionConverter.catchingThrows(
            context = "revokeBinding(terminalDbId=$terminalDbId, employeeId=$employeeId)",
            employeeId = employeeId,
        ) {
            var oldDeviceId: String? = null
            val summary =
                dbQuery {
                    val terminal =
                        repository.findById(terminalDbId)
                            ?: throw PosException(
                                PosErrorCode.TERMINAL_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Terminal $terminalDbId not found",
                            )
                    val hasOpen = fiscalGateway.hasPendingSigning(terminal.id)
                    if (hasOpen) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.Conflict,
                            "Terminal ${terminal.clientId} has unfinished TSE transactions — finish them before revoking",
                        )
                    }
                    oldDeviceId = terminal.deviceId
                    repository.clearBinding(terminal)
                    pairingCodeRepository.consumeAllForTerminal(terminal, currentTimeStamp())
                    terminal.toSummaryDto()
                }
            // Invalidate AFTER the transaction above has committed — see pairDevice for why invalidating
            // pre-commit is a race that leaves a revoked terminal's cached capability stale forever.
            capabilityResolver.invalidate(oldDeviceId)
            emitTerminalSessionTerminated(oldDeviceId, terminalDbId, TerminalSessionTerminatedReason.REVOKED)
            summary
        }

    override suspend fun registerOrUpdate(
        employeeId: String,
        deviceId: String,
        request: TerminalDto.RegisterTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto =
        ExceptionConverter.catchingThrows(
            context = "registerOrUpdate(deviceId=$deviceId, employeeId=$employeeId)",
            employeeId = employeeId,
            deviceId = deviceId,
        ) {
            dbQuery {
                val existing =
                    repository.findByDeviceId(deviceId)
                        ?: throw PosException(
                            PosErrorCode.TERMINAL_NEEDS_PAIRING,
                            HttpStatusCode.Conflict,
                            "Device $deviceId is not bound to any terminal. Pair it from the back office.",
                        )
                val updated =
                    repository.update(
                        terminal = existing,
                        model = request.model,
                        brand = request.brand,
                        serialNr = request.serialNr,
                        swVersion = request.swVersion,
                    ) ?: existing
                updated.toResponseDto()
            }
        }

    override suspend fun recordReportedSwVersion(
        deviceId: String,
        reportedVersion: String?,
    ) {
        val version = reportedVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (version.length > swVersionMaxLength) {
            logger.warn("Ignoring software version reported by device $deviceId: longer than $swVersionMaxLength characters")
            return
        }
        try {
            dbQuery {
                val terminal = repository.findByDeviceId(deviceId) ?: return@dbQuery
                val previous = terminal.terminalSwVersion
                if (previous == version) return@dbQuery
                // A closing without its own Stammdaten snapshot exports slaves.csv from this very
                // row — freeze what those closings emit today before the value moves. Closings that
                // already carry a snapshot are left untouched.
                fiscalGateway.freezeStammdatenBeforeTerminalRewrite()
                repository.update(terminal, model = null, brand = null, serialNr = null, swVersion = version)
                logger.info("Terminal ${terminal.clientId} reports software version $version (was ${previous ?: "-"})")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not record software version $version for device $deviceId: ${e.message}", e)
        }
    }

    override suspend fun getTerminalByDeviceId(deviceId: String): TerminalDto.TerminalResponseDto =
        dbQuery {
            val terminal = repository.findByDeviceId(deviceId) ?: throw terminalNotFoundByDevice(deviceId)
            terminal.toResponseDto()
        }

    override suspend fun getCurrentTerminalByDevice(deviceId: String): TerminalDto.TerminalResponseDto =
        dbQuery {
            val terminal = repository.findByDeviceId(deviceId) ?: throw terminalNotFoundByDevice(deviceId)
            terminal.toResponseDto()
        }

    override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> =
        dbQuery {
            repository.getAll().withPairingExpiry()
        }

    override suspend fun getActiveTerminals(): List<TerminalDto.TerminalSummaryDto> =
        dbQuery {
            repository.getActive().withPairingExpiry()
        }

    /**
     * Maps terminals to summaries, attaching each one's live pairing-code expiry.
     *
     * The expiry is looked up for the whole list in a single query rather than per terminal: this
     * runs on every render of the back office's terminal list, and a per-row lookup would be an N+1.
     * Must be called inside a transaction.
     */
    private fun List<Terminal>.withPairingExpiry(): List<TerminalDto.TerminalSummaryDto> {
        val expiries =
            pairingCodeRepository.findLiveExpiryByTerminal(
                terminalIds = map { it.id.value },
                now = currentTimeStamp(),
            )
        return map { terminal -> terminal.toSummaryDto(expiries[terminal.id.value]?.toString()) }
    }

    override suspend fun updateTerminal(
        employeeId: String,
        terminalId: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto {
        // Rejected here as well as in the UI: the column is a plain varchar, so an unrecognised role
        // would be stored and then fail to parse everywhere that reads it back.
        val role =
            request.role?.let { raw ->
                TerminalRole.entries.firstOrNull { it.name == raw }
                    ?: throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "Unknown terminal role: $raw",
                    )
            }
        // Captured inside the mutating transaction below, acted on only after it commits. Stays false
        // for a name/location-only edit — and for re-saving the role a terminal already has — because
        // a no-op edit must not knock a working device out of its session.
        var roleChanged = false
        var deviceIdToInvalidate: String? = null
        val dto =
            ExceptionConverter.catchingThrows(
                context = "updateTerminal(id=$terminalId, employeeId=$employeeId)",
                employeeId = employeeId,
                entryLogConfig =
                    EntryLogConfig<TerminalDto.TerminalResponseDto>(
                        logType = ChangeEntityType.TERMINAL,
                        logOperation = LogOperation.UPDATE,
                        extractLogData = { updated ->
                            EntryLogData(
                                terminalId.toString(),
                                updated.name ?: updated.terminalId,
                                "Updated terminal $terminalId (role=${updated.role}, name=${updated.name ?: "-"})",
                            )
                        },
                    ),
            ) {
                dbQuery {
                    val terminal =
                        repository.findById(terminalId)
                            ?: throw PosException(
                                PosErrorCode.TERMINAL_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Terminal $terminalId not found",
                            )
                    // Read the stored role BEFORE updateDetails overwrites it — afterwards there is
                    // nothing left to compare the request against.
                    roleChanged = role != null && terminal.role != role.name
                    repository.updateDetails(terminal, request.name, request.location, role?.name)
                    deviceIdToInvalidate = terminal.deviceId
                    terminal.toResponseDto()
                }
            }
        if (!roleChanged) return dto
        // Invalidate AFTER catchingThrows() has fully returned. Because entryLogConfig is set here,
        // catchingThrows wraps our block() in its OWN ambient dbQuery (so the entry-log write is
        // atomic with the mutation); the inner dbQuery above only JOINS that ambient transaction —
        // it does not commit on its own return. Invalidating from inside would run before the real
        // commit, letting a concurrent cache-miss re-cache the OLD role with no later invalidate to
        // correct it — and the ceiling in RouteAuthorizationPlugin would keep enforcing that old
        // role's limits on a terminal demoted for a reason.
        capabilityResolver.invalidate(deviceIdToInvalidate)
        // requiresRepairing = false on purpose: the binding is untouched, so the device resets only
        // the session built on it, re-resolves its role and (for an ORDER_STATION) redoes the
        // station-session exchange. Sending true would clear the device token and strand the device
        // on the pairing screen, waiting for an operator to type a fresh code.
        emitTerminalSessionTerminated(
            deviceId = deviceIdToInvalidate,
            terminalDbId = terminalId,
            reason = TerminalSessionTerminatedReason.ROLE_CHANGED,
            requiresRepairing = false,
        )
        return dto
    }

    override suspend fun deactivateTerminal(
        employeeId: String,
        terminalId: Long,
    ) {
        // Captured inside the mutating transaction below, invalidated only after it commits.
        var deviceIdToInvalidate: String? = null
        var deactivatedTerminalDbId: Long? = null
        var deactivatedClientId: String? = null
        ExceptionConverter.catchingThrows(
            context = "deactivateTerminal(id=$terminalId, employeeId=$employeeId)",
            employeeId = employeeId,
            // Taking a till out of service decides who may sign; it left no audit row (QA rt14).
            operation = "DEACTIVATE_TERMINAL",
            entityType = "TERMINAL",
            auditEntity = { AuditEntityRef(id = terminalId.toString(), name = deactivatedClientId) },
            failedEntityId = terminalId.toString(),
            entryLogConfig =
                EntryLogConfig<Unit>(
                    logType = ChangeEntityType.TERMINAL,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _ ->
                        EntryLogData(terminalId.toString(), "Terminal#$terminalId", "Deactivated terminal $terminalId")
                    },
                ),
        ) {
            dbQuery {
                repository.deactivate(terminalId)
                // A deactivated terminal must not be resurrected by an outstanding pairing code —
                // consume any still-live codes for it now.
                repository.findById(terminalId)?.let { terminal ->
                    pairingCodeRepository.consumeAllForTerminal(terminal, currentTimeStamp())
                    deviceIdToInvalidate = terminal.deviceId
                    deactivatedTerminalDbId = terminal.id.value
                    deactivatedClientId = terminal.clientId
                }
            }
        }
        // Invalidate AFTER catchingThrows() has fully returned. Because entryLogConfig is set here,
        // catchingThrows wraps our block() in its OWN ambient dbQuery (so the entry-log write is
        // atomic with the mutation); the inner dbQuery above only JOINS that ambient transaction —
        // it does not commit on its own return. Invalidating from inside (as before) ran before the
        // real commit, letting a concurrent cache-miss re-cache the still-active (pre-deactivation)
        // capability with no later invalidate to correct it.
        capabilityResolver.invalidate(deviceIdToInvalidate)
        emitTerminalSessionTerminated(
            deviceId = deviceIdToInvalidate,
            terminalDbId = deactivatedTerminalDbId ?: terminalId,
            reason = TerminalSessionTerminatedReason.DEACTIVATED,
        )
    }

    override suspend fun reactivateTerminal(
        employeeId: String,
        terminalId: Long,
    ): TerminalDto.TerminalSummaryDto {
        // Ensure the terminal exists before reactivating.
        val clientId = dbQuery { (Terminal.findById(terminalId) ?: throw terminalNotFound(terminalId)).clientId }

        // Reactivate (audit-logged), mirroring deactivateTerminal.
        ExceptionConverter.catchingThrows(
            context = "reactivateTerminal(id=$terminalId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "REACTIVATE_TERMINAL",
            entityType = "TERMINAL",
            auditEntity = { AuditEntityRef(id = terminalId.toString(), name = clientId) },
            failedEntityId = terminalId.toString(),
            entryLogConfig =
                EntryLogConfig<Unit>(
                    logType = ChangeEntityType.TERMINAL,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { _ ->
                        EntryLogData(terminalId.toString(), "Terminal#$terminalId", "Reactivated terminal $terminalId")
                    },
                ),
        ) {
            dbQuery { repository.reactivate(terminalId) }
        }

        // Best-effort TSE re-verify: reconcile tseClientRegistered against TSE reality.
        // Never fail reactivation because TSE is disabled or unreachable.
        val tseMode = configManager.getTseMode()
        if (tseMode != TseMode.DISABLED) {
            runCatching { fetchRegisteredClientIds() }
                .onSuccess { registeredClientIds ->
                    dbQuery {
                        val terminal = Terminal.findById(terminalId) ?: return@dbQuery
                        val shouldBeRegistered = terminal.clientId in registeredClientIds
                        if (terminal.tseClientRegistered != shouldBeRegistered) {
                            terminal.tseClientRegistered = shouldBeRegistered
                        }
                    }
                }.onFailure { e ->
                    logger.warn("Reactivate: TSE re-verify failed for terminal $terminalId; leaving flag as-is: ${e.message}")
                }
        } else {
            logger.info("Reactivate: TSE disabled; skipping TSE re-verify for terminal $terminalId")
        }

        // Invalidate AFTER the re-verify block above (which may flip tseClientRegistered in its own
        // dbQuery) — invalidating any earlier would let that later write re-cache a stale capability.
        return dbQuery {
            val terminal = Terminal.findById(terminalId) ?: throw terminalNotFound(terminalId)
            capabilityResolver.invalidate(terminal.deviceId)
            terminal.toSummaryDto()
        }
    }

    override suspend fun updateLastSeen(deviceId: String) {
        try {
            dbQuery { repository.updateLastSeen(deviceId) }
        } catch (e: NoSuchElementException) {
            throw terminalNotFoundByDevice(deviceId)
        }
    }

    override suspend fun registerTerminalTse(terminalId: Long): TerminalDto.TerminalSummaryDto {
        // A disabled TSE is a state conflict, not a malformed request: 409 TSE_DISABLED lets a client
        // tell "put the TSE into operation first" apart from a validation failure.
        if (configManager.getTseMode() == TseMode.DISABLED) {
            throw tseDisabled()
        }

        // Find terminal by database ID
        val terminal =
            dbQuery {
                Terminal.findById(terminalId) ?: throw terminalNotFound(terminalId)
            }

        // The flag is the database's belief, not the device's. After a restore or a TSE swap it says
        // "registered" for a client the attached device has never seen, and answering from it told
        // the operator the station was registered while its queue kept failing with
        // CLIENT_NOT_REGISTERED (E2E round 4, R4-6). So ask the device; a stale flag is cleared and
        // the registration below runs for real. Needs the Admin sitting, as registering always did.
        if (terminal.tseClientRegistered) {
            if (terminal.clientId in fetchRegisteredClientIds()) {
                logger.info("Terminal ${terminal.clientId} is already TSE-registered (confirmed by the device)")
                return dbQuery { terminal.toSummaryDto() }
            }
            logger.warn("Terminal ${terminal.clientId} was flagged TSE-registered but the device does not know it; registering")
            dbQuery { terminal.tseClientRegistered = false }
        }

        if (!isTseSafeClientId(terminal.clientId)) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "Terminal clientId '${terminal.clientId}' contains characters a TSE 2.0 rejects",
            )
        }

        // Register with TSE hardware. registerClient throws PosException on failure (including
        // TSE_ADMIN_LOCKED / 423 when the Admin sitting isn't unlocked) — let it propagate
        // unchanged so the client can prompt for the Admin PIN. Do NOT wrap into 500.
        logger.info("Registering terminal ${terminal.clientId} with TSE")
        val tseResult = fiscalGateway.registerClient(terminal.clientId)
        return if (tseResult.success) {
            logger.info("TSE client registered successfully: ${terminal.clientId}")
            var deviceIdToInvalidate: String? = null
            val summary =
                dbQuery {
                    terminal.tseClientRegistered = true
                    deviceIdToInvalidate = terminal.deviceId
                    terminal.toSummaryDto()
                }
            // Invalidate AFTER the transaction above has committed — see pairDevice for why
            // invalidating pre-commit is a race that leaves a stale (unregistered) capability cached.
            capabilityResolver.invalidate(deviceIdToInvalidate)
            summary
        } else {
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "TSE registration returned unsuccessful for ${terminal.clientId}: ${tseResult.message}",
            )
        }
    }

    override suspend fun deregisterTerminalTse(
        employeeId: String,
        terminalId: Long,
    ): TerminalDto.TerminalSummaryDto {
        var deviceIdToInvalidate: String? = null
        val summary =
            ExceptionConverter.catchingThrows(
                context = "deregisterTerminalTse(id=$terminalId, employeeId=$employeeId)",
                employeeId = employeeId,
                operation = "DEREGISTER_TSE_CLIENT",
                entityType = "TERMINAL",
                auditEntity = { dto: TerminalDto.TerminalSummaryDto -> AuditEntityRef(id = dto.id.toString(), name = dto.terminalId) },
                // A refusal names the terminal too, not the "FAILED" placeholder.
                failedEntityId = terminalId.toString(),
            ) {
                if (configManager.getTseMode() == TseMode.DISABLED) throw tseDisabled()
                val terminal = dbQuery { Terminal.findById(terminalId) ?: throw terminalNotFound(terminalId) }
                requireRetired(terminal)
                // Needs the Admin sitting: a locked one answers 423 here, before anything changes.
                val registeredClientIds = fetchRegisteredClientIds()
                requireDeregistrable(terminal.clientId, registeredClientIds)
                requireNoStartedDeviceTransactions(terminal.clientId)

                logger.info("Deregistering terminal ${terminal.clientId} from the TSE")
                val result = fiscalGateway.deregisterClient(terminal.clientId)
                if (!result.success) {
                    throw PosException(
                        PosErrorCode.INTERNAL_ERROR,
                        HttpStatusCode.InternalServerError,
                        "TSE deregistration returned unsuccessful for ${terminal.clientId}: ${result.message}",
                    )
                }
                dbQuery {
                    terminal.tseClientRegistered = false
                    deviceIdToInvalidate = terminal.deviceId
                    terminal.toSummaryDto()
                }
            }
        // Invalidate AFTER the transaction above has committed, exactly as registerTerminalTse does.
        capabilityResolver.invalidate(deviceIdToInvalidate)
        return summary
    }

    /**
     * Only a retired terminal gives up its TSE identity: deactivated, no device bound to it, and
     * nothing of it still waiting for a signature. An active or bound terminal could sign again a
     * moment later, and would then fail with CLIENT_NOT_REGISTERED.
     */
    private suspend fun requireRetired(terminal: Terminal) {
        val refusal =
            dbQuery {
                when {
                    terminal.isActive -> "Terminal ${terminal.clientId} is still active — deactivate it first"
                    terminal.deviceId != null -> "Terminal ${terminal.clientId} is still bound to a device — revoke the binding first"
                    fiscalGateway.hasPendingSigning(terminal.id) -> "Terminal ${terminal.clientId} has unfinished TSE transactions — finish them first"
                    else -> null
                }
            } ?: return
        throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict, refusal)
    }

    /**
     * The device must list the client, and a NORMAL-mode TSE keeps at least one: its self test runs
     * against a registered client (see `TseDriverClient`), so removing
     * the last one would leave the TSE unable to pass the next scheduled self test.
     */
    private suspend fun requireDeregistrable(
        clientId: String,
        registeredClientIds: Set<String>,
    ) {
        val refusal =
            when {
                clientId !in registeredClientIds -> "The TSE does not list $clientId as a registered client — nothing to deregister"
                configManager.getTseMode() == TseMode.NORMAL && registeredClientIds == setOf(clientId) ->
                    "$clientId is the TSE's last registered client — register another terminal first"
                else -> null
            } ?: return
        throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.Conflict, refusal)
    }

    /**
     * TSE 1.x refuses to deregister a client with started transactions
     * (`WORM_ERROR_CLIENT_HAS_UNFINISHED_TRANSACTIONS`); TSE 2.0 does not, and would leave them
     * unfinishable. Refused on both.
     */
    private suspend fun requireNoStartedDeviceTransactions(clientId: String) {
        val started = fiscalGateway.startedTransactionCount(clientId)
        if (started == 0) return
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "The TSE still holds $started unfinished transaction(s) of $clientId — finish them first",
        )
    }

    override suspend fun syncTseRegistrationStatus(): List<TerminalDto.TerminalSummaryDto> {
        // A disabled TSE is a state conflict, not a malformed request: 409 TSE_DISABLED lets a client
        // tell "put the TSE into operation first" apart from a validation failure.
        if (configManager.getTseMode() == TseMode.DISABLED) {
            throw tseDisabled()
        }

        // Get registered client IDs from TSE device (TSE provider now throws PosException on error)
        val registeredClientIds = fetchRegisteredClientIds()

        logger.info("TSE reports ${registeredClientIds.size} registered clients: $registeredClientIds")

        // Cross-reference with all terminals and update flags
        val result =
            dbQuery {
                val allTerminals = repository.getAll()

                var updatedCount = 0
                for (terminal in allTerminals) {
                    val shouldBeRegistered = terminal.clientId in registeredClientIds
                    if (terminal.tseClientRegistered != shouldBeRegistered) {
                        terminal.tseClientRegistered = shouldBeRegistered
                        updatedCount++
                    }
                }

                logger.info("TSE sync complete: updated $updatedCount terminal(s) out of ${allTerminals.size}")
                allTerminals.map { it.toSummaryDto() }
            }
        // Bulk change across potentially every terminal — drop the whole cache rather than
        // invalidating per-deviceId.
        capabilityResolver.invalidateAll()
        return result
    }

    /**
     * The client ids the TSE lists. The provider's own `PosException` (423 while the Admin sitting
     * is locked) propagates unchanged and is never re-wrapped into a 500 (E2E-2026-09-22 R3-6); an
     * unsuccessful answer is `INTERNAL_ERROR` / 500 — see `TseTerminalFiscalGateway.listRegisteredClientIds`.
     */
    private suspend fun fetchRegisteredClientIds(): Set<String> = fiscalGateway.listRegisteredClientIds()

    override suspend fun onBrowserStationRolesChanged(
        previous: Set<TerminalRole>,
        current: Set<TerminalRole>,
    ) {
        val removed = previous - current
        if (removed.isEmpty()) return
        // Plain read transaction, fully committed before any invalidate or push below.
        val affected =
            dbQuery {
                repository
                    .findBoundBrowserTerminals()
                    .filter { TerminalRole.fromCode(it.role) in removed }
                    .map { it.deviceId to it.id.value }
            }
        affected.forEach { (deviceId, terminalDbId) ->
            capabilityResolver.invalidate(deviceId)
            // requiresRepairing = false: the pairing survives, so re-enabling the role lets the
            // same browser back in without a fresh code.
            emitTerminalSessionTerminated(
                deviceId = deviceId,
                terminalDbId = terminalDbId,
                reason = TerminalSessionTerminatedReason.BROWSER_DISABLED,
                requiresRepairing = false,
            )
        }
    }

    /**
     * Best-effort push to any live SSE connection bound to [deviceId]. Called after the
     * terminal mutation has committed so the device can reset itself.
     *
     * [requiresRepairing] says how deep that reset goes, and defaults to the destructive answer the
     * revoke and deactivate callers need: their binding really is gone, so the device must drop its
     * device token and be paired again from a fresh code. A role change passes false — the pairing
     * still holds and only the session built on it is stale, so the device keeps the pairing and
     * rebuilds the session by itself, with nobody standing at it typing a code.
     */
    private suspend fun emitTerminalSessionTerminated(
        deviceId: String?,
        terminalDbId: Long,
        reason: TerminalSessionTerminatedReason,
        requiresRepairing: Boolean = true,
    ) {
        if (deviceId.isNullOrBlank()) return
        val message =
            NotificationDto.TerminalSessionTerminatedDto(
                timestamp = currentTimeStamp().toString(),
                terminalId = terminalDbId.toString(),
                reason = reason.name,
                requiresRepairing = requiresRepairing,
            )
        NotificationHub.sendToDevice(deviceId, message)
    }

    private fun terminalNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Terminal $id not found",
        )

    private fun terminalNotFoundByDevice(deviceId: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Terminal with deviceId=$deviceId not found",
        )

    private fun tseDisabled() =
        PosException(
            PosErrorCode.TSE_DISABLED,
            HttpStatusCode.Conflict,
            "TSE is disabled",
        )
}
