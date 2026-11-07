package org.example.project.features.launch.presentation

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.NotificationConnection
import org.example.project.ui.auth.SessionState

/**
 * Listens to SSE notifications and tears the local session down when the server pushes
 * [NotificationDto.TerminalSessionTerminatedDto].
 *
 * How far the teardown goes is the server's call, carried by
 * [NotificationDto.TerminalSessionTerminatedDto.requiresRepairing]: a revoked or deactivated
 * terminal loses its pairing too (a cold reset), while a role change keeps it (a session reset).
 * Both clear the JWT tokens, persisted user info, login data and permissions, then signal the app
 * root — which is what returns the device to the launch gate.
 *
 * @param clearCachedAuth drops the HTTP client's *cached* bearer token, alongside [tokenStore].
 *   Emptying the token store alone is not enough: Ktor's `BearerAuthProvider` caches whatever
 *   `loadTokens` last returned and reloads it only on a 401, so after a reset the client went on
 *   sending the PREVIOUS person's JWT. Measured: a terminal re-roled to ORDER_STATION opened its
 *   station session correctly and then ran the SSE stream as the admin who had been signed in on it
 *   (`station=false`), and mounted the till UI as that admin. `KtorAuthApiClient` already does this
 *   sweep in both `login()` and `logout()` — "the next session must not inherit this one's cached
 *   token" — and the reset path simply missed it.
 *
 *   A lambda rather than an `HttpClient` or an `AuthApiClient`: this is a session-teardown class,
 *   not an HTTP caller, and the only thing it needs from the network layer is "forget the credential
 *   you are holding". Taking the client would put Ktor auth-plugin internals into a lifecycle class
 *   and force every test to build one; the lambda is exactly the size of the capability and lets a
 *   test count the calls. It is wired to the real sweep in `viewModelModule`.
 */
class TerminalLifecycleHandler(
    private val connection: NotificationConnection,
    private val tokenStore: TokenStore,
    private val localDb: LocalDatabaseManager,
    private val permissionManager: PermissionManager,
    private val sessionState: SessionState,
    private val clearCachedAuth: () -> Unit,
) {
    private val logger = Logger.withTag("TerminalLifecycleHandler")
    private var scope: CoroutineScope? = null
    private var messageJob: Job? = null

    /**
     * Navigation callback invoked after local session state AND the pairing are wiped.
     * Must be set by the app root before or during [start].
     */
    var onColdReset: () -> Unit = {}

    /**
     * Navigation callback invoked after a session-only reset, which leaves the pairing intact.
     *
     * A second callback rather than a parameter on [onColdReset], because the two resets ask the
     * app root for different things: after a cold reset the device is unpaired, so the launch gate
     * only has to fall through to the pairing screen, whereas after a session reset the device is
     * still the same paired terminal and the launch flow has to be RE-RUN — the role is re-read
     * from the server, and an order station then trades its surviving device token for a station
     * JWT. Two callbacks also keep this handler ignorant of `LaunchCoordinator`, which depends on
     * it; a direct call would close that cycle.
     */
    var onSessionReset: () -> Unit = {}

    /**
     * Start collecting terminal-lifecycle messages in [scope]. Returns this handler for chaining.
     */
    fun start(scope: CoroutineScope): TerminalLifecycleHandler {
        stop()
        this.scope = scope
        messageJob =
            scope.launch {
                connection.messages.collectLatest { message ->
                    if (message is NotificationDto.TerminalSessionTerminatedDto) {
                        handleSessionTerminated(message)
                    }
                }
            }
        return this
    }

    /** Stop collecting messages. */
    fun stop() {
        messageJob?.cancel()
        messageJob = null
        scope = null
    }

    /**
     * Public entry point for the 409/401/403 fallback path: any API call that discovers the
     * terminal is revoked/deactivated can trigger the same cold reset.
     */
    fun coldReset(reason: TerminalColdResetReason) {
        logger.i { "Cold reset (${reason.diagnostic})" }
        performColdReset(reason)
    }

    /**
     * The employee session ended (a refresh the server rejected), but the pairing did not. Back to
     * the login screen with the device token and terminal registration intact.
     */
    fun sessionReset() {
        logger.i { "Session reset triggered by API failure" }
        performSessionReset()
    }

    /**
     * The server says this terminal's session is over;
     * [NotificationDto.TerminalSessionTerminatedDto.requiresRepairing] says how much of the device
     * goes with it.
     *
     * A revoked or deactivated terminal (`true`) loses its pairing: the pairing itself is what is
     * wrong. A role change (`false`) does not — it is the same device on the same server, and
     * making a technician walk over and type a fresh pairing code just to turn a till into an
     * order station is a cost with nothing behind it. The session still has to go either way: the
     * JWT it holds was minted for the old role and its claims no longer describe this terminal.
     */
    private fun handleSessionTerminated(message: NotificationDto.TerminalSessionTerminatedDto) {
        logger.i {
            "Terminal session terminated (reason=${message.reason}, terminal=${message.terminalId}, " +
                "requiresRepairing=${message.requiresRepairing})"
        }
        if (message.requiresRepairing) performColdReset(TerminalColdResetReason.SERVER_REVOKED) else performSessionReset()
    }

    private fun performColdReset(reason: TerminalColdResetReason) {
        clearSession()
        // Recorded before the wipe: after it there is nothing left on the device that says why it
        // is asking for a pairing code, which is exactly the question support gets asked.
        localDb.setPreference(LAST_COLD_RESET_REASON, reason.diagnostic)
        localDb.clearDeviceToken()
        localDb.clearTerminalRegistration()
        onColdReset()
    }

    companion object {
        /** Preference key holding the last unpair's [TerminalColdResetReason]. */
        const val LAST_COLD_RESET_REASON = "last_cold_reset_reason"
    }

    /**
     * Everything [performColdReset] clears EXCEPT the device token and terminal registration, so
     * the device stays paired and [onSessionReset] can re-run the launch flow against it.
     */
    private fun performSessionReset() {
        clearSession()
        onSessionReset()
    }

    /**
     * The half both resets share: the credentials, and the identity behind them.
     *
     * [performColdReset] and [performSessionReset] both route through here, so neither can end up
     * with a different notion of what "the credentials are gone" means — which is why
     * [clearCachedAuth] belongs here and not in either caller.
     */
    private fun clearSession() {
        // Stop the SSE stream first so the reset does not race with incoming messages. The stream
        // is authenticated with the token about to be cleared anyway; the screen that owns it
        // re-opens it once the flow has resolved into a session again.
        scope?.launch { connection.disconnect() }
        tokenStore.clear()
        // Both halves of the credential, or neither: the stored token AND the one the HTTP client
        // is holding in memory. See the constructor KDoc.
        clearCachedAuth()
        localDb.clearUserInfo()
        localDb.clearLoginUserData()
        permissionManager.clearRole()
        sessionState.logout()
    }
}
