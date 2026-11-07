package org.example.project.core.network

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.project.BuildKonfig
import org.example.project.core.auth.JwtDecoder
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.core.utils.platformIoDispatcher

/**
 * Server-Sent Events implementation of [NotificationConnection].
 *
 * Opens a plain HTTP(S) GET stream to the server's `/api/notifications/stream`
 * endpoint via the Ktor client SSE plugin, parses each event polymorphically via
 * [SseEventParser], tracks `Last-Event-ID` for gap-free replay on reconnect, and
 * reconciles authoritative state ([NotificationReconciler]) on every successful
 * connect. Reconnection uses exponential backoff via [ReconnectPolicy].
 *
 * The transport is receive-only — SSE is server→client, so [NotificationConnection]
 * no longer exposes `sendMessage`.
 *
 * @property client Ktor HttpClient with the SSE plugin installed
 * @property tokenStore Token store for the current access token
 * @property authApiClient Authentication API client for token validation/refresh
 * @property localDb Source of the API base URL
 * @property reconciler Reconcile-on-connect service (full sync + pending approvals)
 * @property reconnectPolicy Exponential-backoff reconnect state machine
 * @property appVersion This build's version, reported on every connect so the server keeps the
 *   terminal's DSFinV-K software version current after an app update
 * @property ioDispatcher where the token store is read. The access token is sealed and opening it can block on the
 *   Keystore or keychain, while `connect` is called from a ViewModel's scope — the UI thread.
 */
class SseNotificationConnection(
    private val client: HttpClient,
    private val tokenStore: TokenStore,
    private val authApiClient: AuthApiClient,
    private val localDb: LocalDatabaseManager,
    private val reconciler: NotificationReconciler,
    private val reconnectPolicy: ReconnectPolicy,
    private val appVersion: String = BuildKonfig.APP_VERSION,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : NotificationConnection {
    private val logger = Logger.withTag("SseNotificationConnection")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _connectionState =
        MutableStateFlow<NotificationConnection.ConnectionState>(
            NotificationConnection.ConnectionState.Disconnected,
        )
    override val connectionState: StateFlow<NotificationConnection.ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<NotificationDto.NotificationMessage>(replay = 0, extraBufferCapacity = 64)
    override val messages: SharedFlow<NotificationDto.NotificationMessage> = _messages.asSharedFlow()

    private val _errors = MutableSharedFlow<NotificationConnection.NotificationConnectionError>(replay = 1)
    override val errors: SharedFlow<NotificationConnection.NotificationConnectionError> = _errors.asSharedFlow()

    private val _latestTseStatus = MutableStateFlow<NotificationDto.TseStatusUpdateDto?>(null)
    override val latestTseStatus: StateFlow<NotificationDto.TseStatusUpdateDto?> = _latestTseStatus.asStateFlow()

    private var lastEventId: String? = null
    private var streamJob: Job? = null
    private var stopped = false
    private var lastConnectedUrl: String? = null

    /**
     * Who the live stream was authenticated as — see [principalOf]. Guarded by [lifecycle]; null while no stream is
     * open. A token refresh for the same person leaves it unchanged, so a routine renewal never restarts the stream.
     */
    private var boundPrincipal: String? = null

    /**
     * The endpoint of the most recent [connect], so [requestImmediateReconnect] can re-open the same stream without its
     * caller having to carry one. Read and written outside [lifecycle], like the `stopped` read in [scheduleReconnect]:
     * a reference cannot tear, and in practice it is written with the one endpoint this app ever opens.
     */
    private var lastEndpoint: String? = null

    /**
     * Serializes starting and stopping a stream with the writes a stream job makes to shared state. Held only across
     * code that does not suspend, so a slow subscriber cannot hold up connect() or disconnect() through it.
     */
    private val lifecycle = Mutex()

    /**
     * The current stream, guarded by [lifecycle]: bumped when a stream starts and by [disconnect]. A stream job and the
     * reconnect timer it schedules act only while their generation is current. Cancelling was not enough: a cancelled
     * job keeps running up to its next suspension point, and a reconnect timer already past its `stopped` check opened
     * a new stream after disconnect() — either could record the previous server's TSE status after the next connect()
     * had cleared it.
     */
    private var generation = 0L

    override fun resetReconnectAttempts() {
        reconnectPolicy.reset()
        stopped = false
    }

    /**
     * Reconnects now rather than waiting out [ReconnectPolicy]'s slow interval — up to a minute in which a station whose
     * Wi-Fi has already returned still shows a dead stream (E2E-2026-09-15 N2).
     *
     * Passes `scheduledBy = null`, the value the public [connect] uses for "any other caller". A generation would mark
     * this as a reconnect timer and so **skip** the re-entrancy guard, which is the one guard that matters here: Android's
     * `onAvailable` also fires on a network change while the stream is healthy, and a second stream makes the server
     * force-close the first — the 1 Hz ping-pong that guard exists to prevent.
     *
     * Suppressed once [disconnect] has run, checked *before* [resetReconnectAttempts] because that call clears `stopped`
     * itself: returning Wi-Fi must not resurrect a stream the app deliberately closed on logout or service stop. After
     * [dispose] the launch is already inert — the scope is cancelled, so the body never runs.
     */
    override fun requestImmediateReconnect() {
        val endpoint = lastEndpoint ?: return
        if (stopped) return
        resetReconnectAttempts()
        scope.launch { connect(endpoint, scheduledBy = null) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun clearErrors() {
        // The `errors` flow uses replay = 1, so its last value is replayed to every
        // new subscriber. A session-ending AuthenticationFailed (expired token) would
        // otherwise be replayed to the NotificationViewModel that is recreated after
        // the user logs back in, immediately kicking them to the login screen again.
        // Errors are session-scoped — drop the retained one.
        _errors.resetReplayCache()
    }

    override suspend fun connect(endpoint: String) = connect(endpoint, scheduledBy = null)

    /**
     * @param scheduledBy the generation of the stream whose reconnect timer is calling, or null for any other caller.
     *   Such a reconnect is dropped once that stream is no longer current. It skips the re-entrancy guard, which can
     *   still see the stream that scheduled it finishing and would then drop the reconnect for good.
     */
    private suspend fun connect(
        endpoint: String,
        scheduledBy: Long?,
    ) {
        lastEndpoint = endpoint
        val url = buildStreamUrl(endpoint)
        val startedAt =
            lifecycle.withLock {
                if (scheduledBy != null) {
                    if (scheduledBy != generation || stopped) return
                } else if (_connectionState.value == NotificationConnection.ConnectionState.Connected ||
                    streamJob?.isCompleted == false
                ) {
                    // Re-entrancy guard: bail if already connected OR a connect attempt is still in
                    // flight. The state only flips to Connected *inside* the launched streamJob after
                    // sseSession() succeeds, so guarding on Connected alone leaves a window where a
                    // second connect() (e.g. VM init + ON_RESUME firing together on desktop startup)
                    // opens a second stream. The server enforces one stream per employee and force-
                    // closes the older one, which the client then reconnects — a 1Hz ping-pong loop.
                    // isCompleted, not isActive: a job that is not started yet, or is cancelled but still
                    // unwinding, is not active but still owns its stream.
                    //
                    // ...unless the live stream was opened for someone else. The guard used to bail
                    // regardless, so a stream opened with the previous user's stored token (Android
                    // restarting the notification service after a crash, before anyone logged in) stayed
                    // bound to that user after the next login: its connect() landed here and was dropped
                    // (emulator pass 4, NOTE-4). A different session ends the old stream and opens a new one.
                    if (!belongsToAnotherSession()) {
                        logger.w { "Notification stream already connected or connecting" }
                        return
                    }
                    logger.i { "Notification stream belongs to a previous session; re-binding it to the current one" }
                    endCurrentStream()
                }

                if (url != lastConnectedUrl) {
                    // TSE status and the last event id are per-server state. This connection is a process-wide
                    // singleton whose base URL can change mid-process (pairing screen, LaunchCoordinator
                    // server-address change) — a stream opened against a different server must not keep showing the
                    // previous server's cached status, whether or not the new one ever pushes its own.
                    _latestTseStatus.value = null
                    // An event id names a position in one server's stream. Sent to another server as Last-Event-ID,
                    // it would ask that server to resume after an unrelated event of its own. Cleared here, under the
                    // lock and before this stream's generation starts: a superseded stream cannot write it back.
                    lastEventId = null
                }
                lastConnectedUrl = url

                // Re-arm the reconnect loop. `disconnect()` sets `stopped = true`; since this is a
                // process-wide singleton, a later session that connects again (e.g. desktop re-login,
                // which calls connect with reset=false) must clear the flag, or the first stream drop
                // would silently kill auto-reconnect for the whole session.
                stopped = false
                _connectionState.value = NotificationConnection.ConnectionState.Connecting
                generation
            }

        when (ensureFreshToken()) {
            TokenFreshness.FRESH -> Unit
            TokenFreshness.AUTH_FAILED -> {
                logger.e { "Unable to obtain valid access token for SSE connection" }
                failAuthentication(startedAt, NotificationConnection.AuthFailureReason.TOKEN_RENEWAL_FAILED)
                return
            }
            // The server could not be asked. That is the reconnect ladder's business, not the
            // session's: this arm used to report an authentication failure, which MainScreen turns
            // into a logout, so restarting pos-server signed the operator out mid-service
            // (E2E-2026-09-19). The token is untouched and the next attempt renews it.
            TokenFreshness.UNREACHABLE -> {
                logger.w { "Token could not be renewed: server unreachable. Reconnecting." }
                _connectionState.value = NotificationConnection.ConnectionState.Disconnected
                scheduleReconnect(endpoint, startedAt, firstFailure = reconnectPolicy.attempts == 0)
                return
            }
        }

        val token = withContext(ioDispatcher) { tokenStore.accessToken }
        if (token == null) {
            logger.e { "No access token available for SSE connection" }
            failAuthentication(startedAt, NotificationConnection.AuthFailureReason.NO_ACCESS_TOKEN)
            return
        }

        logger.i { "Connecting to SSE stream: $url" }

        val gen = startedAt + 1
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val session =
                        client.sseSession(url) {
                            header("Authorization", "Bearer $token")
                            lastEventId?.let { header("Last-Event-ID", it) }
                            header(DeviceIdentifiers.APP_VERSION_HEADER, appVersion)
                            // The stream is intentionally long-lived: disable the client's global
                            // requestTimeoutMillis for this call so the HTTP request is not force-killed
                            // every few minutes (which would cause a needless reconnect storm). The
                            // socket timeout is left intact — combined with the server's 15s keepalive
                            // comment, it is what detects a dead/half-open server and triggers reconnect.
                            timeout { requestTimeoutMillis = Long.MAX_VALUE } // Ktor's INFINITE_TIMEOUT_MS
                        }
                    ifCurrent(gen) {
                        _connectionState.value = NotificationConnection.ConnectionState.Connected
                        reconnectPolicy.reset()
                    }
                    logger.i { "SSE stream connected successfully" }

                    // Reconcile authoritative state on every (re)connect.
                    launch { runCatching { reconciler.reconcile { emitIfCurrent(gen, _messages, it) } } }

                    session.incoming.collect { sse ->
                        // Generation-checked like every other write: a superseded job must not hand its
                        // Last-Event-ID to the stream that replaced it, which may be talking to another server.
                        sse.id?.let { id -> ifCurrent(gen) { lastEventId = id } }
                        val data = sse.data ?: return@collect
                        var parsed: NotificationDto.NotificationMessage? = null
                        SseEventParser.parse(
                            data,
                            { parsed = it },
                            { error -> scope.launch { emitIfCurrent(gen, _errors, error) } },
                        )
                        val message = parsed ?: return@collect
                        // Recorded before the emit. A consumer created in between seeds from this status and
                        // then receives it too, which at worst suppresses one transition warning — the footer
                        // is right either way. The other order could seed a new consumer with the previous
                        // status and never deliver it the new one.
                        if (message is NotificationDto.TseStatusUpdateDto) {
                            ifCurrent(gen) { _latestTseStatus.value = message }
                        }
                        // Emitted from this one collector, in arrival order. A coroutine per message on
                        // Dispatchers.Default kept no order, and the TSE transition warnings compare each status
                        // with the one before it. The buffer means this suspends only for a subscriber 64 behind.
                        // While emit suspends, the socket is not read, keepalive pings included, so a subscriber
                        // stuck that long can let the socket timeout end the stream. Suspending is still right:
                        // dropping would silently lose session-terminated, data-change and billing events, while
                        // a timeout only costs a reconnect.
                        emitIfCurrent(gen, _messages, message)
                    }

                    // The incoming flow completed → server closed the stream.
                    logger.w { "SSE stream closed by server" }
                    ifCurrent(gen) {
                        _connectionState.value = NotificationConnection.ConnectionState.Disconnected
                        scheduleReconnect(endpoint, gen, firstFailure = reconnectPolicy.attempts == 0)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "SSE stream failed" }
                    ifCurrent(gen) {
                        _connectionState.value = NotificationConnection.ConnectionState.Disconnected
                        scheduleReconnect(endpoint, gen, firstFailure = reconnectPolicy.attempts == 0)
                    }
                }
            }
        lifecycle.withLock {
            // disconnect(), or another connect() that got here first, ran while the token was checked.
            if (generation != startedAt) {
                job.cancel()
                return
            }
            generation = gen
            boundPrincipal = principalOf(token)
            // Backstop: the re-entrancy guard should leave no live job here, but one replaced uncancelled would keep a
            // stream open that disconnect() no longer references and so can never close.
            streamJob?.cancel()
            streamJob = job
            // Started inside the same section: start() only dispatches the job and does not suspend. Started after the
            // lock, a connect() in between would find this job not yet active, pass its guard and replace it, and this
            // call would then start a job nothing references.
            job.start()
        }
    }

    override suspend fun disconnect() {
        logger.i { "Disconnecting SSE stream" }
        lifecycle.withLock {
            stopped = true
            endCurrentStream()
        }
        clearErrors()
    }

    /**
     * The startup WIPE's half of the stream (NOTE-8, emulator pass 5): a till whose notification service
     * Android revived after a crash had re-opened the stream with the previous person's stored token, and the
     * wipe emptied the store but left that stream running — bound to that person, with nobody logged in, until
     * the next login re-bound it. A billing request addressed to them arrived on it and was shown to no one.
     *
     * With no stored token everything is stale, including a connect still in flight, which [endCurrentStream]'s
     * generation bump cancels. With a token, only a stream bound to another principal goes: a connect in flight
     * has no bound principal yet and belongs to whoever stored that token.
     */
    override fun closeIfSessionEnded() {
        scope.launch {
            lifecycle.withLock {
                if (withContext(ioDispatcher) { tokenStore.accessToken } != null && !belongsToAnotherSession()) return@withLock
                logger.i { "Closing a notification stream whose session has ended" }
                stopped = true
                endCurrentStream()
            }
        }
    }

    /**
     * Ends the current generation. The cancelled job still runs up to its next suspension point, and a reconnect timer
     * it scheduled may already be waiting; neither can write shared state or open a stream now. Call under [lifecycle].
     */
    private fun endCurrentStream() {
        generation++
        streamJob?.cancel()
        streamJob = null
        boundPrincipal = null
        _connectionState.value = NotificationConnection.ConnectionState.Disconnected
    }

    /**
     * True when the stored credential now names a different principal than the one the live stream was opened with —
     * another employee logged in, or the session is gone. Call under [lifecycle].
     */
    private suspend fun belongsToAnotherSession(): Boolean {
        val bound = boundPrincipal ?: return false
        return withContext(ioDispatcher) { tokenStore.accessToken }?.let(::principalOf) != bound
    }

    /**
     * Runs [block] under [lifecycle] while [gen] is the current stream; a superseded stream's job leaves shared state alone.
     * Deliberately not inline: [block] is then a plain lambda, so the compiler rejects a suspend call in it and nothing
     * can suspend while the lock is held.
     */
    private suspend fun ifCurrent(
        gen: Long,
        block: () -> Unit,
    ) {
        lifecycle.withLock { if (gen == generation) block() }
    }

    /**
     * Emits [value] into [flow] while [gen] is the current stream, running [alongside] first in the same [lifecycle]
     * section. The check and a non-suspending emit share that section, so a disconnect() cannot slip between them. Only
     * with the buffer full — a subscriber that has fallen behind — does the emit suspend, outside the lock so that
     * subscriber cannot hold up connect() or disconnect(). Not inline, for the same reason as [ifCurrent].
     */
    private suspend fun <T> emitIfCurrent(
        gen: Long,
        flow: MutableSharedFlow<T>,
        value: T,
        alongside: () -> Unit = {},
    ) {
        val handled =
            lifecycle.withLock {
                if (gen != generation) return
                alongside()
                // tryEmit only dispatches to the buffer and waiting subscribers; it never suspends. Emitting after
                // releasing the lock instead would reopen the stale-emit window.
                flow.tryEmit(value)
            }
        if (!handled) flow.emit(value)
    }

    /**
     * Ends the connect attempt begun at generation [startedAt] with an authentication failure, unless disconnect() or
     * a connect() that got further superseded it while the token was checked. `errors` replays its last value and
     * disconnect() has already cleared it: a stale failure would greet the next session and sign the user out.
     */
    private suspend fun failAuthentication(
        startedAt: Long,
        reason: NotificationConnection.AuthFailureReason,
    ) = emitIfCurrent(startedAt, _errors, NotificationConnection.NotificationConnectionError.AuthenticationFailed(reason)) {
        _connectionState.value = NotificationConnection.ConnectionState.Disconnected
    }

    private fun scheduleReconnect(
        endpoint: String,
        gen: Long,
        firstFailure: Boolean,
    ) {
        if (stopped) return
        val wasExhausted = reconnectPolicy.isExhausted
        val delayMs = reconnectPolicy.nextDelay()
        if (reconnectPolicy.isExhausted && !wasExhausted) {
            // Fast ladder spent: tell the user once, then keep retrying quietly so a station that
            // lost Wi-Fi recovers without anyone tapping refresh (E2E-2026-09-15 N2). Deliberately no
            // `return` — the scheduling tail below has to run on this path too, or the notice would
            // once again be the point where reconnects stop.
            logger.w { "Max fast reconnection attempts reached; falling back to slow retries" }
            _connectionState.value = NotificationConnection.ConnectionState.Failed
            scope.launch {
                emitIfCurrent(
                    gen,
                    _errors,
                    NotificationConnection.NotificationConnectionError.ConnectionFailed(
                        NotificationConnection.ConnectionFailureReason.RETRIES_EXHAUSTED,
                    ),
                )
            }
        }
        if (firstFailure) {
            scope.launch {
                emitIfCurrent(
                    gen,
                    _errors,
                    NotificationConnection.NotificationConnectionError.ConnectionFailed(
                        NotificationConnection.ConnectionFailureReason.STREAM_LOST,
                    ),
                )
            }
        }
        logger.i { "Scheduling SSE reconnect in ${delayMs}ms" }
        scope.launch {
            delay(delayMs)
            connect(endpoint, scheduledBy = gen)
        }
    }

    /**
     * True when the server will accept the token this connection is about to send.
     *
     * [TokenStore.isAccessTokenExpired] answers a *renewal* question — "does this token expire
     * within the five-minute buffer" — and is deliberately true long before the token stops
     * working. It is not the question asked here, and reading it as "the token is unusable" is
     * what turned a healthy session into a blocking authentication failure:
     *  - a token whose whole lifetime is shorter than the buffer (a station on
     *    `JWT_ACCESS_TOKEN_EXPIRY_MINUTES=1`) is "expired" from the moment it is issued, and
     *    nothing renews it, because renewal is driven by a 401 from the call below;
     *  - a token that was *just* renewed is "expired" again immediately, so even a successful
     *    device-token re-exchange was reported as "unable to refresh".
     *
     * So the question goes to the server. A 2xx from `/api/auth/validate` means the token that
     * arrived was accepted: either the one held, or the one the Ktor `Auth` plugin obtained while
     * answering the 401 this call provoked (a person's refresh, a station's device-token
     * re-exchange). Only a throw — a refusal that could not be renewed, or a transport failure —
     * is an authentication failure, and the caller's token read below picks up whatever the plugin
     * stored.
     */

    /** What a renewal attempt learned about the token — see [ensureFreshToken]. */
    private enum class TokenFreshness {
        /** Usable now. */
        FRESH,

        /** The server rejected the session. The operator has to sign in again. */
        AUTH_FAILED,

        /** The server could not be asked. Says nothing about the session; retry. */
        UNREACHABLE,
    }

    /**
     * Renews the access token if it has expired.
     *
     * The three outcomes are deliberately distinct. Collapsing "could not ask" into "rejected" is
     * what made a server restart sign the operator out: every exception, a refused socket included,
     * reported an authentication failure, and `MainScreen` logs out on one. An unknown exception
     * counts as UNREACHABLE for the same reason — a retry costs a reconnect, a wrongful logout costs
     * the person at the till their session in the middle of service. A session that really is over
     * still ends it: the HTTP client's own refresh calls `onUnauthorized` on a 401 from
     * `/auth/refresh`.
     */
    private suspend fun ensureFreshToken(): TokenFreshness =
        try {
            // Off the caller's thread: without a stored expiry this decodes the sealed access token.
            if (!withContext(ioDispatcher) { tokenStore.isAccessTokenExpired() }) {
                TokenFreshness.FRESH
            } else {
                // Triggers automatic refresh via the HttpClient Auth plugin; throws on failure.
                authApiClient.validateToken()
                TokenFreshness.FRESH
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkApiException) {
            logger.w(e) { "Token renewal could not reach the server" }
            TokenFreshness.UNREACHABLE
        } catch (e: PosApiException) {
            val rejected = e.httpStatus == 401 || e.httpStatus == 403
            logger.e(e) { "Token renewal refused with ${e.httpStatus}" }
            if (rejected) TokenFreshness.AUTH_FAILED else TokenFreshness.UNREACHABLE
        } catch (e: Exception) {
            logger.e(e) { "Failed to ensure fresh token" }
            TokenFreshness.UNREACHABLE
        }

    private fun buildStreamUrl(endpoint: String): String {
        val base = localDb.getApiBaseUrl().trimEnd('/')
        return "$base/${endpoint.removePrefix("/")}"
    }

    override fun dispose() {
        scope.cancel()
    }
}

/**
 * The identity a notification stream is opened for: the JWT subject and employee number, so a renewed token for the
 * same person maps to the same principal while another person's token does not. A token that is not a decodable JWT
 * is its own principal.
 */
internal fun principalOf(accessToken: String): String = JwtDecoder.decode(accessToken)?.let { "${it.employeeId}/${it.employeeNumber}" } ?: accessToken
