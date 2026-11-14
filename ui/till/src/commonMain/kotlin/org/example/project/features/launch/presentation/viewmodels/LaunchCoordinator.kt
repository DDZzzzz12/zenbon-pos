package org.example.project.features.launch.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.core.auth.JwtDecoder
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.SettingManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.isCertificateTrustFailure
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.network.isPinningSupported
import org.example.project.core.notification.PlatformNotificationService
import org.example.project.core.notification.markLoggedInAndStartNotifications
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.ServerUrlKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.utils.ServerAddress
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.core.utils.ServerUrlVerdict
import org.example.project.core.utils.platformIoDispatcher
import org.example.project.features.launch.presentation.TerminalColdResetReason
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.nav.DeviceResolution
import org.example.project.features.launch.presentation.nav.LaunchInputs
import org.example.project.features.launch.presentation.nav.StationBlock
import org.example.project.features.launch.presentation.nav.resolveDestination
import org.example.project.features.setup.data.remote.SetupApiClient
import org.example.project.features.terminal.data.PinFailure
import org.example.project.features.terminal.data.RootCaPinner
import org.example.project.ui.auth.SessionState

/**
 * The single async authority for the launch/auth flow. Runs the setup + device-token checks and
 * exposes the resolved [AppDestination]. Session status is fed in via [setSessionStatus] (bridged
 * from the Compose-backed SessionState in AppNavigation) so [resolveDestination] stays pure; the
 * station marker is copied in the same way, by [updateInputs].
 *
 * For an ORDER_STATION it also performs the station-session exchange (order-station shell,
 * 2026-09-06): the device token is traded for a station JWT, the token store / user info /
 * permission manager are seeded from the response, and [sessionState] is marked logged in — after
 * which the normal `Main` mounts. Nothing about the role is persisted beyond
 * `TokenStore.stationRole`; the server is the authority on every launch.
 *
 * The device-token read and the station-session storage run on [ioDispatcher]: opening or sealing a
 * token can block on the Keystore or keychain, and this coordinator's scope is the UI thread.
 */
class LaunchCoordinator(
    private val setupApiClient: SetupApiClient,
    private val terminalApiClient: TerminalApiClient,
    private val localDb: LocalDatabaseManager,
    private val tokenStore: TokenStore,
    private val permissionManager: PermissionManager,
    private val sessionState: SessionState,
    private val lifecycleHandler: TerminalLifecycleHandler,
    private val settingManager: SettingManager,
    /** Started when the station's exchange opens its session, as a login starts it for a person. */
    private val notificationService: PlatformNotificationService,
    /** How often a blocked station re-checks on its own; null switches it off (tests on virtual time). */
    private val stationRecheckIntervalMs: Long? = STATION_BLOCK_RECHECK_MS,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    private val rootCaPinner: RootCaPinner = RootCaPinner(localDb),
) : ViewModel() {
    private val log = Logger.withTag("LaunchCoordinator")
    private val inputs = MutableStateFlow(LaunchInputs())

    private val _serverAddressError = MutableStateFlow<UiError?>(null)

    /** Why the last [changeServerAddress] was not saved; cleared by [clearServerAddressError]. */
    val serverAddressError: StateFlow<UiError?> = _serverAddressError.asStateFlow()

    val destination: StateFlow<AppDestination> =
        inputs
            .map { resolveDestination(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppDestination.Resolving)

    private val _refusals = Channel<StationRefusal>(Channel.CONFLATED)

    /**
     * A refused station-session exchange (401/409 revoked, 403 deactivated / no shell for the
     * role, or a station token this build cannot read), for the pairing screen to explain.
     *
     * A one-shot event, not state: a [StateFlow] conflates a second *identical* refusal into no
     * emission at all — so a repeat warning is simply lost — and re-fires its last value every
     * time the app root re-enters composition. A conflated channel is the honest shape: each
     * refusal is delivered exactly once, a refusal raised before the collector attaches is still
     * delivered (a [kotlinx.coroutines.flow.SharedFlow] would drop it), and none is ever repeated.
     */
    val refusals: Flow<StationRefusal> = _refusals.receiveAsFlow()

    init {
        refresh()
        // The one trigger for the exchange: the station-session screen is showing. A station has
        // no login screen, so the exchange is the only way it ever enters a session — on the first
        // launch, right after pairing, and again whenever a session ENDS mid-shift. Driving it
        // from the resolved destination instead of from each caller is what fixed the last case:
        // `resolveDevice` had already run, so a station whose session was dropped sat on the
        // launch screen for good. The destination is a StateFlow, so a failed attempt that leaves
        // the destination unchanged does not re-fire — retrying stays [refresh]'s job.
        viewModelScope.launch {
            destination.collect { if (it == AppDestination.StationSession) openStationSession() }
        }
    }

    /**
     * The one way [inputs] is ever written, so the station marker cannot go stale.
     *
     * [resolveDestination] must stay pure — it may not read the token store — so
     * [LaunchInputs.stationSession] is a *copy* of `TokenStore.isStation`, and a copy is only ever
     * as good as its refresh. Refreshing it here, on every mutation, is what makes that total:
     * the marker is written by the exchange and cleared by a person's login and by every reset, and
     * none of those routes through this class. A stale `true` would let a person's session walk
     * into Main on an order station (the defect this guards); a stale `false` would send a station
     * that has just completed its exchange back through it.
     *
     * Read once, outside the lambda: `MutableStateFlow.update` re-runs its transform under
     * contention, and the marker lives in the platform key-value store.
     */
    private fun updateInputs(transform: (LaunchInputs) -> LaunchInputs) {
        val stationSession = tokenStore.isStation
        inputs.update { transform(it).copy(stationSession = stationSession) }
    }

    fun setSessionStatus(status: SessionStatus) = updateInputs { it.copy(sessionStatus = status) }

    fun refresh() {
        updateInputs { it.awaitingTheServer() }
        runLaunchCheck()
    }

    /**
     * The server has changed this terminal's role: the session is gone, the pairing is not, and the
     * launch flow has to be re-run against the same device.
     *
     * **One [updateInputs], and that is the whole point.** The app root used to push the new session
     * status and then call [refresh], which is two mutations — and [destination] recomputes on the
     * FIRST of them, while [LaunchInputs.device] still holds the PREVIOUS `Paired(role)`. On an
     * order station that intermediate is (UNAUTHENTICATED + a stale ORDER_STATION), which resolves
     * to [AppDestination.StationSession] — the one trigger for the station-session exchange. The
     * device fired it against a server already on the new role, was answered 403 ("no shell for role
     * CASHIER"), and [refuse] cold-reset and UN-PAIRED it: exactly the technician's walk and
     * hand-typed pairing code that `requiresRepairing = false` exists to avoid.
     *
     * The status is pushed rather than waited for because the app root's bridge only fires on the
     * next recomposition, and the device check below is a network round-trip that could land first —
     * leaving a re-roled CASHIER resolving to Main on a token store that was just emptied. Both
     * halves therefore have to land together: the status, so a session that is already over cannot
     * be believed, and the device reset, so the role it was minted for cannot be read.
     */
    fun onTerminalRoleChanged() {
        updateInputs { it.awaitingTheServer().copy(sessionStatus = SessionStatus.UNAUTHENTICATED) }
        runLaunchCheck()
    }

    /**
     * The launch inputs while the server is being asked again. The role goes with them: it is the
     * server's answer, and holding the previous one is what let a stale destination be computed.
     */
    private fun LaunchInputs.awaitingTheServer(): LaunchInputs =
        copy(
            requiresSetup = null,
            device = DeviceResolution.Checking,
            offline = false,
            stationBlocked = null,
            insecureServerUrl = storedServerUrlIsInsecure(),
            certificateUntrusted = false,
        )

    /** A stored plain-HTTP LAN address: the device token must never be sent there, so nothing probes it. */
    private fun storedServerUrlIsInsecure(): Boolean = ServerUrlPolicy.evaluate(localDb.getApiBaseUrl()) != ServerUrlVerdict.ACCEPTED

    /** The setup + device round-trip both entry points run; only what they reset first differs. */
    private fun runLaunchCheck() {
        // Never probe a plain-HTTP LAN address: the setup check would send the device token there.
        if (inputs.value.insecureServerUrl) return
        viewModelScope.launch {
            var certificateUntrusted = false
            val setup =
                try {
                    // Bounded: an unreachable address would otherwise keep the launch spinner up for minutes
                    // (15 s connect timeout x the client's retries). The retry button is the way back in.
                    withTimeoutOrNull(SETUP_CHECK_TIMEOUT_MS) { setupApiClient.checkSetupStatus() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    certificateUntrusted = e.isCertificateTrustFailure()
                    if (e !is NetworkApiException && e !is PosApiException) log.e(e) { "setup check failed" }
                    null
                }
            if (setup == null) {
                markOffline(certificateUntrusted)
                return@launch
            }
            if (setup.requiresSetup) {
                // Read locally, as for offline: the device check never runs from here, and the setup
                // screen may offer a server-address change only to a device that is not paired.
                val paired = deviceTokenStored()
                updateInputs { it.copy(requiresSetup = true, deviceTokenStored = paired) }
                return@launch
            }
            updateInputs { it.copy(requiresSetup = false) }
            resolveDevice()
        }
    }

    /**
     * The one way into the offline screen. Whether a device token is stored is read here, from local
     * storage, because the server that would confirm the pairing is exactly what cannot be reached —
     * and it decides whether that screen may offer a server-address change.
     */
    private suspend fun markOffline(certificateUntrusted: Boolean = false) {
        val paired = deviceTokenStored()
        updateInputs { it.copy(offline = true, deviceTokenStored = paired, certificateUntrusted = certificateUntrusted) }
    }

    /** [markOffline] for the callbacks that cannot suspend. */
    private fun markOfflineSoon() {
        viewModelScope.launch { markOffline() }
    }

    /** Whether a device token is stored. The token is sealed, so the read runs on [ioDispatcher]. */
    private suspend fun deviceTokenStored(): Boolean = withContext(ioDispatcher) { localDb.getDeviceToken() != null }

    /**
     * Points this device at another server and re-runs the launch: the offline screen's way out when
     * a fresh install starts on the platform default (`http://localhost:8080/`), which on a tablet or
     * emulator is the device itself.
     *
     * Only ever for a device that is NOT paired. This is the logic half of the guard; the offline and
     * setup-required screens hide the action for a paired device
     * ([AppDestination.Offline.canChangeServerAddress], [AppDestination.Setup.canChangeServerAddress]).
     * Only the pairing screen writes the address itself, and only because it never shows while a
     * device token is stored.
     * The device token is a bearer credential for the server that issued it and rides on every
     * request, so re-pointing a paired till would hand it to whatever address was typed; and a fiscal
     * terminal's pairing, TSE client and journal live on that one server. A paired device that
     * cannot reach its server has a network or server fault, which a new address does not fix. The
     * token is read here, at the moment of the change, rather than trusted from the destination.
     *
     * @return true when the address was saved and the launch re-run.
     */
    fun changeServerAddress(address: String): Boolean {
        // Called from the UI thread, so a sealed token that cannot be opened right now must not throw here:
        // an unreadable token counts as paired, which refuses the change.
        val paired = runCatching { localDb.getDeviceToken() != null }.getOrDefault(true)
        if (paired) {
            log.w { "server address change refused: this device is paired" }
            _serverAddressError.value =
                UiError.Unknown(
                    titleKey = LaunchKeys.serverAddressErrorTitle,
                    messageKey = LaunchKeys.serverAddressLockedWhilePaired,
                )
            return false
        }
        val baseUrl = ServerAddress.normalize(address)
        if (baseUrl == null) {
            _serverAddressError.value =
                UiError.Unknown(
                    titleKey = LaunchKeys.serverAddressErrorTitle,
                    messageKey = LaunchKeys.serverAddressInvalid,
                )
            return false
        }
        if (ServerUrlPolicy.evaluate(baseUrl) != ServerUrlVerdict.ACCEPTED) {
            _serverAddressError.value =
                UiError.Unknown(
                    titleKey = LaunchKeys.serverAddressErrorTitle,
                    messageKey = ServerUrlKeys.insecure,
                )
            return false
        }
        _serverAddressError.value = null
        // SettingManager, not LocalDatabaseManager: it writes the same stored key the HTTP client
        // reads per request, and also updates the flow the pairing screen shows the address from.
        settingManager.setApiBaseUrl(baseUrl)
        log.i { "server address changed to $baseUrl; retrying launch" }
        refresh()
        return true
    }

    /**
     * Trusts the server's local-CA root on a device that is already paired (it was paired before the server
     * moved behind Caddy), from the certificate check code shown in the back office. The root is downloaded
     * from the stored address over an unverified connection and accepted only if it hashes to [checkCode];
     * the device token is not sent anywhere until that has passed. Then the launch re-runs.
     */
    fun pinServerCertificate(checkCode: String) {
        viewModelScope.launch { pinAndRelaunch(localDb.getApiBaseUrl(), checkCode, newAddress = null) }
    }

    /**
     * Saves the https:// address a till with a stored plain-HTTP address was moved to — optionally with the
     * certificate [checkCode], pinned against that address first — and re-runs the launch. Nothing is stored
     * when the pin fails.
     */
    fun saveHttpsAddress(
        address: String,
        checkCode: String?,
    ) {
        viewModelScope.launch {
            val baseUrl = ServerAddress.normalize(address)
            if (baseUrl == null || ServerUrlPolicy.evaluate(baseUrl) != ServerUrlVerdict.ACCEPTED) {
                _serverAddressError.value =
                    UiError.Unknown(titleKey = LaunchKeys.serverAddressErrorTitle, messageKey = LaunchKeys.serverAddressInvalid)
                return@launch
            }
            pinAndRelaunch(baseUrl, checkCode, newAddress = baseUrl)
        }
    }

    private suspend fun pinAndRelaunch(
        baseUrl: String,
        checkCode: String?,
        newAddress: String?,
    ) {
        val code = checkCode?.trim().orEmpty()
        if (code.isNotEmpty() && isPinningSupported) {
            val failure = rootCaPinner.pin(baseUrl, code)
            if (failure != null) {
                log.w { "certificate pin refused: $failure" }
                _serverAddressError.value =
                    UiError.Unknown(
                        titleKey = LaunchKeys.serverAddressErrorTitle,
                        messageKey =
                            when (failure) {
                                PinFailure.CODE_INVALID -> TerminalPairingKeys.errorRootCodeInvalid
                                PinFailure.FETCH_FAILED -> TerminalPairingKeys.errorRootFetchFailed
                                PinFailure.MISMATCH -> TerminalPairingKeys.errorRootMismatch
                            },
                    )
                return
            }
        }
        _serverAddressError.value = null
        newAddress?.let { settingManager.setApiBaseUrl(it) }
        refresh()
    }

    fun clearServerAddressError() {
        _serverAddressError.value = null
    }

    private suspend fun resolveDevice() {
        val beforeRead = DeviceTokenProvider.writeMark()
        val token = withContext(ioDispatcher) { localDb.getDeviceToken() }
        if (token.isNullOrBlank()) {
            updateInputs { it.copy(device = DeviceResolution.NoToken) }
            return
        }
        // Handed to the request-time holder, which the device check below sends as X-Device-Token. A setup check that
        // caught the key store not ready leaves the holder inside its no-retry window, and the check would go out
        // without the header; seeding it also saves a second read of the sealed token. Seeded only if nothing wrote the
        // holder while the read ran: a cold reset that cleared the token meanwhile must not have it put back, and a
        // request that loaded it meanwhile already holds it.
        val seeded = DeviceTokenProvider.seedIfUnchangedSince(beforeRead, token)
        // Not seeded, and the holder now empty: a cold reset cleared the token during the read, so the one read is revoked.
        // The device check would go out without X-Device-Token, and the server's 401 read as the offline screen. Pairing
        // is where the reset leads — its own onColdReset resolves NoToken too, so the two agree whichever lands last.
        if (!seeded && DeviceTokenProvider.token == null) {
            log.i { "The device token was cleared while it was being read — resolving to pairing" }
            updateInputs { it.copy(device = DeviceResolution.NoToken) }
            return
        }
        runCatchingApi { terminalApiClient.getCurrentTerminalByDevice() }
            .onSuccess { terminal ->
                localDb.setTerminalRegistered(terminal.terminalId, terminal.id)
                val role = TerminalRole.fromCode(terminal.role)
                updateInputs { it.copy(device = DeviceResolution.Paired(terminal.terminalId, role)) }
                // No exchange call here: resolving to Paired(ORDER_STATION) resolves the
                // destination to StationSession, and the collector in `init` runs the exchange.
            }.onError { uiError ->
                val code = (uiError as? UiError.FromServer)?.code
                when (code) {
                    in TERMINAL_AUTH_ERRORS -> {
                        localDb.clearDeviceToken()
                        localDb.clearTerminalRegistration()
                        updateInputs { it.copy(device = DeviceResolution.NoToken) }
                    }
                    in PERSON_SESSION_ERRORS -> endPersonSession(code)
                    else -> {
                        log.w { "device check failed (non-auth): $code" }
                        markOfflineSoon()
                    }
                }
            }
    }

    /**
     * The server answered the device lookup about a PERSON's session. The device is not implicated —
     * the lookup is authenticated by its device token — and the server is plainly reachable, so this
     * is neither the offline screen nor an unpair (NEW-1 showed "server unreachable" here).
     *
     * It is the same session reset a refused token refresh takes: the person signs in again, the
     * pairing stays, and the reset re-runs the launch, whose next lookup goes out with no session.
     * The lookup is sent on the device credential only, so this is a guard, not a path a current
     * server takes.
     */
    private fun endPersonSession(code: PosErrorCode?) {
        log.w { "device check answered about the person's session ($code); ending the session, keeping the pairing" }
        lifecycleHandler.sessionReset()
    }

    /**
     * The station's login. On 200 the response is adopted and the session marked authenticated;
     * on 401 / 409 / 403 the device is cold-reset and the flow returns to pairing with a
     * [StationRefusal] on [refusals]; on 424 / 500 the device stays paired and the launch screen
     * names the server-side fault with a retry ([blockStation]) — as it does when the server issued
     * the session but this device cannot store it right now ([adoptOrRefuse]); on anything else (a
     * network blip) the flow shows the offline screen, whose retry re-runs [refresh].
     */
    private suspend fun openStationSession() {
        runCatchingApi { terminalApiClient.openStationSession() }
            .onSuccess { session -> adoptOrRefuse(session) }
            .onError { uiError ->
                val server = uiError as? UiError.FromServer
                val blocked = STATION_BLOCKS[server?.httpStatus]
                when {
                    // The shop's browser-station setting, not a verdict on the device: stay paired
                    // (blockStation never resets) so re-enabling works without re-pairing.
                    server?.code == PosErrorCode.BROWSER_STATION_DISABLED -> {
                        log.w { "station session blocked: browser stations disabled" }
                        blockStation(server, StationBlock.BROWSER_STATIONS_DISABLED)
                    }
                    server != null && server.httpStatus in STATION_REFUSALS -> {
                        log.w { "station session refused (${server.httpStatus}): ${server.rawDetail}" }
                        refuse(
                            StationRefusal(
                                server.copy(
                                    titleKey = LaunchKeys.stationRefusedTitle,
                                    messageKey = LaunchKeys.stationRefusedMessage,
                                ),
                            ),
                        )
                    }
                    server != null && blocked != null -> {
                        log.w { "station session blocked (${server.httpStatus}): ${server.rawDetail}" }
                        // The server's own rawDetail is dropped: the 424 body is an internal phrase and the
                        // 500's detail names server internals, and neither tells staff what to have done.
                        blockStation(
                            blocked,
                            server.copy(
                                titleKey = LaunchKeys.stationBlockedTitle,
                                messageKey = blocked.messageKey,
                                rawDetail = null,
                            ),
                        )
                    }
                    else -> {
                        log.w { "station session exchange failed (non-auth): $uiError" }
                        markOfflineSoon()
                    }
                }
            }
    }

    /**
     * A server-side fault: not a refusal, and not a network problem. The device stays paired and the
     * token store is untouched; the launch screen shows [blocked]'s reason with a retry that succeeds
     * as soon as someone repairs the server side.
     *
     * The server's own `rawDetail` is dropped: the 424 body is an internal phrase and the 500's
     * detail names server internals, and neither tells a member of staff what to have done.
     */
    private fun blockStation(
        server: UiError.FromServer,
        blocked: StationBlock,
    ) = blockStation(
        blocked,
        server.copy(
            titleKey = LaunchKeys.stationBlockedTitle,
            messageKey = blocked.messageKey,
            rawDetail = null,
        ),
    )

    /**
     * A station that cannot start yet through no fault of its pairing: a server-side fault, or a
     * session this device cannot store right now. Not a refusal, and not a network problem. The
     * device stays paired and its device token is untouched; the launch screen shows [blocked]'s
     * reason with a retry that succeeds as soon as the server side is repaired or the key store
     * answers again.
     *
     * The typed refusal event is reused so the operator gets the sentence immediately.
     */
    private fun blockStation(
        blocked: StationBlock,
        error: UiError,
    ) {
        updateInputs { it.copy(stationBlocked = blocked, offline = false) }
        scheduleStationRecheck()
        _refusals.trySend(StationRefusal(error))
    }

    /** The one pending automatic re-check of a blocked station; replaced, never duplicated. */
    private var stationRecheck: Job? = null

    /**
     * A blocked station holds no station JWT and no event stream, so a server-side fix — or a role
     * change, e.g. the admin switching it back to CASHIER — can never be pushed to it. It used to sit
     * on the blocked screen until someone tapped Wiederholen (E2E-2026-09-18 #16). Re-run exactly
     * what that button runs, once per [STATION_BLOCK_RECHECK_MS]; a block that repeats reschedules.
     */
    private fun scheduleStationRecheck() {
        val interval = stationRecheckIntervalMs ?: return
        stationRecheck?.cancel()
        stationRecheck =
            viewModelScope.launch {
                delay(interval)
                if (inputs.value.stationBlocked != null) refresh()
            }
    }

    /**
     * Seeds what a login seeds: the token store, the persisted user info, and the live gate.
     *
     * A station token this build cannot decode is a refusal, never a half-initialized shell: the
     * same null decode makes `PermissionManager.readRoleSnapshot` return no role at all, and a
     * null role is a documented bypass in the client's navigation gate.
     *
     * A session this device cannot store is a block, not a refusal: sealing the token fails while the
     * Keystore or keychain is unusable, and a refusal's cold reset would delete the device token — for
     * good when that token is legacy plaintext the store could not re-seal. The station shows
     * [StationBlock.LOCAL_STORAGE_UNAVAILABLE] with a retry instead, and the pairing stays intact.
     *
     * A cold reset that lands while the session is being stored wins: the stored session is discarded
     * rather than marked logged in on a device already back at pairing.
     */
    private suspend fun adoptOrRefuse(session: TerminalDto.StationSessionResponseDto) {
        val userInfo = stationUserInfo(session)
        if (userInfo == null) {
            log.w { "station token could not be decoded; refusing the session" }
            refuse(
                StationRefusal(
                    UiError.Unknown(
                        titleKey = LaunchKeys.stationRefusedTitle,
                        messageKey = LaunchKeys.stationTokenUnreadable,
                    ),
                ),
            )
            return
        }
        val stored = storeStationSession(session, userInfo)
        if (inputs.value.device !is DeviceResolution.Paired) {
            log.w { "The device was reset while the station session was being stored — discarding the session" }
            discardStationSession()
            return
        }
        stored.onFailure { e ->
            val blocked = StationBlock.LOCAL_STORAGE_UNAVAILABLE
            blockStation(
                blocked,
                UiError.Unknown(titleKey = LaunchKeys.stationBlockedTitle, messageKey = blocked.messageKey, cause = e),
            )
            return
        }
        permissionManager.setReportedCapabilities(session.capabilities)
        // The exchange is the station's login, so it opens the session the way a login does: session first,
        // then the notification service. On Android the service is the only thing that opens the stream, so a
        // station that skipped it never received a push — not even the server's session-terminated one.
        sessionState.markLoggedInAndStartNotifications(notificationService)
        // Adopt the marker into the launch inputs at once. The app root bridges the new session
        // status back in through [setSessionStatus], which refreshes it too — but until that lands
        // the inputs still say "not a station session", and any mutation in between would recompute
        // the destination from that stale answer and send this station back through the exchange.
        refreshStationMarker()
    }

    /** Re-reads the station marker from the token store, leaving every other launch input alone. */
    private fun refreshStationMarker() = updateInputs { it }

    /**
     * Stores the station token and the terminal-device employee on [ioDispatcher]. When that fails —
     * sealing the token throws while the Keystore or keychain is unusable — the stored session is
     * discarded, so the block that follows leaves no half-stored session, and no earlier principal's
     * user info, behind. The device token and terminal registration are not touched.
     */
    private suspend fun storeStationSession(
        session: TerminalDto.StationSessionResponseDto,
        userInfo: EmployeeInfoDto.EmployeeInfo,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                tokenStore.storeStationSession(session)
                localDb.setUserInfo(userInfo)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Could not store the station session locally — blocking the station, keeping the pairing" }
                discardStationSession()
                Result.failure(e)
            }
        }

    /**
     * Clears the station token and the persisted user info. Never reaches the cipher, so it cannot fail the way the store
     * did: the token store removes its rows, and the user info is blanked with `""`, which is never sealed.
     */
    private fun discardStationSession() {
        tokenStore.clear()
        localDb.clearUserInfo()
    }

    /**
     * The one teardown a refused station shares with every other revoked terminal: the cold reset
     * clears tokens, device registration, persisted user info, the permission manager and the
     * session, and drops the SSE stream. Doing less here left an AUTHENTICATED session and a live
     * capability set behind — a device re-paired as CASHIER then walked straight past the login
     * screen with an empty token store.
     */
    private fun refuse(refusal: StationRefusal) {
        // The SERVER refused this device token (409 revoked / 403 deactivated), which is what
        // licenses an unpair here; see TerminalColdResetReason.
        lifecycleHandler.coldReset(TerminalColdResetReason.DEVICE_TOKEN_REJECTED)
        updateInputs { it.copy(device = DeviceResolution.Rejected) }
        // Emitted last on purpose: the cold reset flips the session to UNAUTHENTICATED, and the app
        // root clears queued overlays on that transition. A warning raised before it is swallowed.
        _refusals.trySend(refusal)
    }

    /**
     * The shared terminal-device employee, as PermissionManager.readRoleSnapshot will read it
     * back — or null when the access token is not a token this build can decode.
     */
    private fun stationUserInfo(session: TerminalDto.StationSessionResponseDto): EmployeeInfoDto.EmployeeInfo? {
        val employeeId = JwtDecoder.extractUserInfo(session.accessToken)?.employeeId ?: return null
        return EmployeeInfoDto.EmployeeInfo(
            id = employeeId,
            username = TERMINAL_DEVICE_USERNAME,
            employeeNumber = session.terminalId,
            role = EmployeeRole.TERMINAL_DEVICE.name,
            trainingMode = false,
            capabilities = session.capabilities,
        )
    }

    fun onPaired(terminal: TerminalDto.TerminalResponseDto) {
        localDb.setTerminalRegistered(terminal.terminalId, terminal.id)
        // Pairing reached through an untrusted certificate: the setup check never got an answer, so the
        // launch is still waiting on it. The certificate is pinned now — run the check again.
        if (inputs.value.requiresSetup == null) {
            refresh()
            return
        }
        val role = TerminalRole.fromCode(terminal.role)
        // A station block belongs to the pairing it was raised for: the new pairing runs its own exchange.
        updateInputs {
            it.copy(device = DeviceResolution.Paired(terminal.terminalId, role), offline = false, stationBlocked = null)
        }
    }

    /**
     * Closing the one-shot channel completes [refusals] for any collector still attached. Safe in
     * `onCleared`, which cannot launch on the (already cancelled) viewModelScope.
     */
    override fun onCleared() {
        _refusals.close()
        super.onCleared()
    }

    fun onColdReset() = updateInputs { it.copy(device = DeviceResolution.NoToken, offline = false) }

    /**
     * The offline screen's way out for a paired device whose server will not answer (it moved, was
     * re-addressed or replaced): the operator's own re-pair, the same cold reset the nav footer
     * offers. Straight to the pairing screen, where the server address can be changed — [offline]
     * and the stale launch answers are dropped, or the offline screen would win the precedence
     * again and the device would stay locked to the address it can no longer reach.
     */
    fun repairFromOffline() {
        lifecycleHandler.coldReset(TerminalColdResetReason.OPERATOR_REPAIR)
        updateInputs {
            it.copy(
                offline = false,
                certificateUntrusted = false,
                requiresSetup = false,
                device = DeviceResolution.NoToken,
                deviceTokenStored = false,
            )
        }
    }

    fun pairDifferentDevice() {
        localDb.clearDeviceToken()
        localDb.clearTerminalRegistration()
        updateInputs { it.copy(device = DeviceResolution.NoToken) }
    }

    companion object {
        private const val TERMINAL_DEVICE_USERNAME = "terminal-device"

        /**
         * 401 expired / rejected device token, 409 revoked / unpaired, 403 deactivated /
         * non-fiscal / no shell for the role. 401 belongs here because the route plugin and the
         * device-principal challenge both answer with it; without it an expired device token read
         * as a network blip and looped on the offline screen forever.
         */
        private val STATION_REFUSALS = setOf(401, 403, 409)

        /**
         * The two answers that are the server's own fault. 424 = TSE mode is NORMAL and this
         * terminal has no registered TSE client; 500 = the shared terminal-device employee is not
         * provisioned. Both used to fall through to the offline screen, which sent staff chasing a
         * network fault that did not exist — and neither is a refusal: the pairing is intact, and
         * retrying is exactly right once the server side is repaired.
         */
        private val STATION_BLOCKS =
            mapOf(
                424 to StationBlock.TSE_CLIENT_NOT_REGISTERED,
                500 to StationBlock.SERVER_INSTALLATION_INCOMPLETE,
            )

        /**
         * The device lookup's answers that mean THIS DEVICE is not (or no longer) paired: cold reset
         * to pairing. `AUTH_INVALID_CREDENTIALS` is the device-principal's 401 for an unknown or
         * expired device token. It is only unambiguous because the lookup carries no person's bearer
         * — the server gives the same code to an unverifiable bearer, which must never un-pair.
         */
        private val TERMINAL_AUTH_ERRORS =
            setOf(
                PosErrorCode.TERMINAL_NEEDS_PAIRING,
                PosErrorCode.AUTH_FORBIDDEN,
                PosErrorCode.RESOURCE_NOT_FOUND,
                PosErrorCode.AUTH_INVALID_CREDENTIALS,
            )

        /** Answers about a person's session, not the device: a session reset, never offline or unpair. */
        private val PERSON_SESSION_ERRORS = setOf(PosErrorCode.AUTH_SESSION_EXPIRED)
    }
}

/** How often a blocked order station re-checks on its own. */
internal const val STATION_BLOCK_RECHECK_MS = 30_000L

/** How long the launch waits for the server's setup status before showing the offline screen. */
internal const val SETUP_CHECK_TIMEOUT_MS = 15_000L
