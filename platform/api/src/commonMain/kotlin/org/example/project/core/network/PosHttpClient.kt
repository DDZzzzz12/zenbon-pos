package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.RefreshTokensParams
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.RefreshTokenRequest
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiExceptionDecoder
import org.example.project.core.utils.AuthTransport
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.core.utils.platformIoDispatcher
import io.ktor.client.HttpClientConfig as KtorClientConfig

/** Refresh endpoint, relative to the base URL — which always ends in `/`. */
private const val REFRESH_PATH = "api/auth/refresh"

/** Station-session exchange, relative to the base URL — see the station branch in [createPosHttpClient]. */
private const val STATION_SESSION_PATH = "api/v1/terminals/session"

/**
 * The one authenticated HTTP client for both clients.
 *
 * The Ktor [Auth] bearer provider attaches the access token from [tokenStore]; on a 401 it
 * silently refreshes via `api/auth/refresh` (rotating the stored tokens) and retries the
 * original request. Only when the refresh itself fails does [onUnauthorized] fire, which the
 * caller wires to sign-out. A station (TokenStore.stationRole != null) holds no refresh token and
 * instead re-exchanges its device token at `api/v1/terminals/session`; only a refused exchange
 * fires [onUnauthorized]. A station whose device token cannot be read right now gets no renewal and
 * no [onUnauthorized] either — the request fails, the pairing stays, a later request tries again.
 *
 * **Do NOT add an `HttpResponseValidator` that signs out on any 401** — it races the Auth
 * plugin's refresh and logs the user out on the very 401 that refresh is about to recover.
 * Sign-out has exactly one trigger: a genuinely failed refresh below.
 *
 * @param baseUrlResolver resolves the API base URL per request; must end in `/`.
 *   composeApp passes `LocalDatabaseManager::getApiBaseUrl`; adminApp passes `{ "/" }` because
 *   the panel is served from pos-server's own origin.
 * @param onTokensRefreshed fired after a successful silent refresh has stored the new tokens,
 *   role and capabilities. Apps use it to re-read what they cache; a refresh does NOT flip
 *   [SessionState.status] (only a FAILED refresh signs out), so nothing recomposes on its own.
 * @param extraConfig per-app plugins. composeApp installs SSE / ContentEncoding / HttpTimeout
 *   here; adminApp installs nothing, because in a browser `Accept-Encoding` is a forbidden
 *   header, decompression is the browser's job, and fetch has no connect/socket timeouts.
 *   composeApp also installs its `ServerUrlGuard` through `extraConfig`; do NOT move that guard
 *   into this shared client — adminApp is served same-origin by pos-server and must keep working
 *   over plain http on shops that have not re-run the installer.
 * @param refreshTransport [RefreshTransport.COOKIE] for adminApp: renewal posts to
 *   `api/auth/refresh` with no body and the browser attaches the HttpOnly refresh cookie. Also
 *   works with an EMPTY token store, which is how a reloaded page resumes its session.
 * @param storageDispatcher where every [tokenStore] read and write runs: the bearer load, the renewal's checks and
 *   the stored answer. composeApp seals the tokens, and opening or sealing one can block on the Android Keystore or
 *   the desktop keychain for seconds, while the request that triggers it is often launched from the UI thread. The
 *   [onUnauthorized] and [onTokensRefreshed] callbacks still run in the caller's context.
 */
fun createPosHttpClient(
    engine: HttpClientEngine,
    tokenStore: TokenStore,
    onUnauthorized: () -> Unit,
    onDeviceRejected: () -> Unit = onUnauthorized,
    onTokensRefreshed: () -> Unit = {},
    baseUrlResolver: () -> String,
    logger: Logger,
    logLevel: LogLevel = LogLevel.INFO,
    refreshTransport: RefreshTransport = RefreshTransport.BODY,
    storageDispatcher: CoroutineDispatcher = platformIoDispatcher(),
    extraConfig: KtorClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(engine) {
        // Non-2xx responses are decoded by the shared error layer (apiCall/ProblemDetail),
        // not thrown by Ktor.
        expectSuccess = false

        install(ContentNegotiation) {
            json(
                Json {
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                },
            )
        }

        install(Logging) {
            this.logger = logger
            this.level = logLevel
            sanitizeHeader { header ->
                header == HttpHeaders.Authorization || header.lowercase().contains("token")
            }
        }

        install(Auth) {
            // A 401 on the login POST is the server's answer to the credentials, not an expired
            // session: never refresh-and-retry it. Ktor's default treats every 401 as "re-authorize
            // and resend", and because login is sent without a bearer (sendWithoutRequest below)
            // the plugin resent it once without even calling refreshTokens — two login POSTs per
            // wrong password (FO-36), which also doubles the server's failed-attempt count.
            reAuthorizeOnResponse { response ->
                response.status == HttpStatusCode.Unauthorized &&
                    !response.call.request.url.encodedPath
                        .contains("/auth/login")
            }
            bearer {
                // A screen change can cancel the coroutine that triggered this refresh (e.g. a
                // ViewModel scope dying mid-request). If the cancellation lands after the server
                // rotated the token but before this client (or the browser, for cookie transport)
                // finishes processing the response, the caller is left holding a rotated-out
                // credential; replaying it past the server's short grace window revokes every
                // session for the employee. Run the refresh to completion regardless of the
                // triggering request's fate — applies to both transports.
                nonCancellableRefresh = true
                loadTokens {
                    withContext(storageDispatcher) { tokenStore.storedBearer(refreshTransport) }
                }
                refreshTokens {
                    if (refreshTransport == RefreshTransport.COOKIE) {
                        return@refreshTokens refreshViaCookie(
                            tokenStore = tokenStore,
                            baseUrl = baseUrlResolver(),
                            onRefused = onDeviceRejected,
                            onStored = onTokensRefreshed,
                            logger = logger,
                            storageDispatcher = storageDispatcher,
                        )
                    }

                    val refresh = withContext(storageDispatcher) { tokenStore.refreshToken }
                    if (refresh == null) {
                        val isStation = withContext(storageDispatcher) { tokenStore.isStation }
                        if (isStation) {
                            // No device token to exchange right now — the key store is not ready and the provider is
                            // inside its retry window — is treated like a transport failure: onUnauthorized is the cold
                            // reset, which would clear a device token that is merely unreadable, and the pairing with it.
                            if (DeviceTokenProvider.currentToken() == null) return@refreshTokens null
                            return@refreshTokens exchangeStationSession(
                                tokenStore = tokenStore,
                                baseUrl = baseUrlResolver(),
                                onRefused = onUnauthorized,
                                onStored = onTokensRefreshed,
                                storageDispatcher = storageDispatcher,
                            )
                        }

                        // No refresh token → the session is unrecoverable. Sign out ONLY if a session
                        // existed (an access token was present). With neither token there is no session at
                        // all — e.g. the launch gate validating its X-Device-Token before any employee has
                        // logged in; a 401 there is a stale-device-token signal for the caller to handle
                        // (clear token → re-pair), not a session-expiry. Firing the cold reset here hijacks
                        // that 401 and loops the launch gate on its "checking" screen.
                        val hadSession = withContext(storageDispatcher) { tokenStore.accessToken != null }
                        if (hadSession) onUnauthorized()
                        return@refreshTokens null
                    }
                    try {
                        val response =
                            client.post(baseUrlResolver() + REFRESH_PATH) {
                                markAsRefreshTokenRequest()
                                contentType(ContentType.Application.Json)
                                setBody(RefreshTokenRequest(refresh))
                            }
                        if (response.status == HttpStatusCode.OK) {
                            // /auth/refresh answers a LoginResponse (tokens nested under `token`),
                            // the same shape as /auth/login — NOT a bare TokenResponse.
                            val login = response.body<LoginResponse>()
                            if (login.token.refreshToken == null) {
                                // Body transport always gets its refresh token back; a missing one is a
                                // broken answer. Sign out rather than store a session that cannot renew.
                                onUnauthorized()
                                return@refreshTokens null
                            }
                            withContext(storageDispatcher) { tokenStore.storeRefreshedSession(login) }

                            // Tell the app the stored answer changed. Writing the store is not
                            // enough on its own: composeApp caches the resolved set in
                            // `PermissionManager`, and adminApp memoises its sidebar — so without a
                            // push, a refresh updated the store and neither client's GATE ever
                            // re-read it. That left a group change waiting for logout, which is the
                            // "revocation waits for token expiry" complaint defect D5 was about.
                            //
                            // A lambda, like `onUnauthorized`, so the lookup is deferred and this
                            // stays free of a DI cycle back through the HTTP client.
                            onTokensRefreshed()

                            BearerTokens(login.token.accessToken, login.token.refreshToken)
                        } else if (response.status == HttpStatusCode.Unauthorized ||
                            response.status == HttpStatusCode.Forbidden
                        ) {
                            // Refresh genuinely rejected (token expired/revoked) -> sign out.
                            onUnauthorized()
                            null
                        } else {
                            // The server answered, but not about this session: a 500 from a server
                            // mid-restart, a 502 from a proxy. Signing out on that logs the till out
                            // (and, on composeApp, unpaired it) because the server hiccupped.
                            null
                        }
                    } catch (e: CancellationException) {
                        // With nonCancellableRefresh, navigation does not cancel this coroutine — a
                        // CancellationException here comes from something like the client/engine being
                        // closed, which is not an auth failure and must not sign the user out.
                        throw e
                    } catch (e: Exception) {
                        // The refresh never reached the server - connection refused, timeout, no
                        // network. That says nothing about whether the session is still valid, and
                        // treating it as a rejection is what unpaired both tills when pos-server was
                        // restarted under them (E2E-2026-09-19). The request fails; the session stays.
                        null
                    }
                }
                sendWithoutRequest { request ->
                    val path = request.url.encodedPath
                    path.startsWith("/api/") &&
                        !path.contains("/auth/login") &&
                        !path.contains("/auth/refresh") &&
                        !path.endsWith("/terminals/session")
                    // The one public health probe is GET /health (outside /api/, so the prefix test
                    // above already exempts it). A contains("/health") clause also dropped the
                    // bearer on the authenticated GET /api/tse/health (TSE_ADMIN) - a 401 on every
                    // owner page load (FO-37).
                }
            }
        }

        defaultRequest {
            try {
                header(DeviceIdentifiers.DEVICE_ID_HEADER, DeviceInfoProvider.getDeviceId())
            } catch (e: IllegalStateException) {
                // Android's provider needs initialize(context) and may not have run yet during
                // startup; the header is omitted rather than failing the request. Logged rather
                // than swallowed: a permanently missing initialize() call would otherwise drop
                // X-Device-ID on every request in silence, and terminal registration and fiscal
                // device binding both depend on that header.
                logger.log("X-Device-ID omitted: device provider not initialized (${e.message})")
            }
            if (refreshTransport == RefreshTransport.COOKIE) header(AuthTransport.HEADER, AuthTransport.COOKIE)
        }

        // X-Device-Token: a plugin, not a defaultRequest line, because the token may first have to be loaded.
        install(DeviceTokenHeader)

        extraConfig()
    }.also { it.installTransportErrorMapping() }

/**
 * Drops the bearer tokens the [Auth] provider has cached, so the next request re-reads [TokenStore].
 *
 * Ktor's bearer provider caches whatever `loadTokens` last returned and reloads it only after a 401,
 * so emptying the token store alone leaves the previous session's JWT riding on every request. Call it
 * wherever a session's tokens are cleared or replaced: login, logout, a terminal reset, and the
 * cashier terminal's startup WIPE.
 */
fun HttpClient.forgetCachedBearer() {
    authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
}

/**
 * Sends this request on the device credential alone: no person's `Authorization: Bearer`, and no
 * refresh-and-retry when it is answered 401.
 *
 * For routes authenticated by `X-Device-Token` whose 401 must be the DEVICE's verdict. pos-server's
 * route plugin refuses any request carrying an expired or unverifiable bearer (401
 * `AUTH_SESSION_EXPIRED` / `AUTH_INVALID_CREDENTIALS`) before the device-principal check runs, so a
 * person's dead token riding along replaced the device's own answer with a session code (NEW-1: the
 * launch read it as "server unreachable"), and an unverifiable one could read as a rejected device
 * and un-pair the till. A person's session is renewed or ended by the [Auth] provider on the calls
 * that are about the person — not on this one.
 *
 * The [AuthCircuitBreaker] attribute is what does it: the bearer provider does not append the header
 * to a marked request, and the [Auth] plugin hands a marked request's 401 straight back.
 */
fun HttpRequestBuilder.onDeviceCredentialOnly() {
    attributes.put(AuthCircuitBreaker, Unit)
}

/**
 * The bearer to attach from the stored tokens, or null when this client holds no usable session. Reads the store, so
 * the caller runs it on the storage dispatcher.
 */
private fun TokenStore.storedBearer(refreshTransport: RefreshTransport): BearerTokens? {
    val access = accessToken ?: return null
    val refresh = refreshToken
    return when {
        refresh != null -> BearerTokens(access, refresh)
        // A station holds an access token and NO refresh token — renewal re-exchanges the device
        // token (see refreshTokens). Before this arm the station never sent its bearer at all and
        // every JWT route answered 401.
        isStation -> BearerTokens(access, null)
        // Cookie transport: the refresh token is a cookie this client never holds.
        refreshTransport == RefreshTransport.COOKIE -> BearerTokens(access, null)
        else -> null
    }
}

/**
 * Renews a login-less station by re-exchanging its device token instead of refreshing (design §5.1).
 *
 * The device token reaches the request through [DeviceTokenProvider], which composeApp's
 * `LocalDatabaseManager` keeps in sync and loads on demand, and which [DeviceTokenHeader] turns into the
 * `X-Device-Token` header — so the exchange authenticates by simply being sent. [onRefused] fires ONLY when the
 * exchange is refused: revoke (409) or deactivate (403) are exactly the cases where returning to
 * pairing is right. A transport failure returns null WITHOUT calling anything — the request fails,
 * the token stays, the next request tries again. No proactive timer: this reactive path covers every
 * case, and a timer would be a second path to test.
 *
 * @return the new bearer, or null when the session could not be renewed.
 */
private suspend fun RefreshTokensParams.exchangeStationSession(
    tokenStore: TokenStore,
    baseUrl: String,
    onRefused: () -> Unit,
    onStored: () -> Unit,
    storageDispatcher: CoroutineDispatcher,
): BearerTokens? =
    try {
        val response =
            client.post(baseUrl + STATION_SESSION_PATH) {
                markAsRefreshTokenRequest()
            }
        when (response.status) {
            HttpStatusCode.OK -> {
                val session = response.body<TerminalDto.StationSessionResponseDto>()
                withContext(storageDispatcher) { tokenStore.storeStationSession(session) }
                onStored()
                BearerTokens(session.accessToken, null)
            }
            HttpStatusCode.Conflict, HttpStatusCode.Forbidden -> {
                // 403 BROWSER_STATION_DISABLED is the SHOP's setting, not a verdict on this device:
                // un-pairing would force a re-pair after the manager re-enables browser stations.
                // The request just fails; the launch flow / SSE push own the recovery.
                val disabled =
                    response.status == HttpStatusCode.Forbidden &&
                        PosApiExceptionDecoder.decode(response).code == PosErrorCode.BROWSER_STATION_DISABLED
                if (!disabled) onRefused()
                null
            }
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * Stores a `/auth/refresh` answer: tokens, role, and capabilities — refreshing the token refreshes the
 * capability set with it, so a group change reaches the client's gates without a logout (defect D5).
 */
private fun TokenStore.storeRefreshedSession(login: LoginResponse) {
    storeTokens(
        accessToken = login.token.accessToken,
        refreshToken = login.token.refreshToken,
        expiresIn = login.token.expiresIn,
    )
    role = login.employee.role
    // A refresh refreshes the capability set and the footer's "who am I" label with it, so a group
    // change reaches the client without waiting for a logout.
    capabilities = login.employee.capabilities.ifEmpty { null }
    permissionGroupCode = login.employee.permissionGroupCode
    permissionGroupName = login.employee.permissionGroupName
}

/**
 * Renews through the HttpOnly refresh cookie (adminApp).
 *
 * The request carries no body: the browser attaches the cookie, `defaultRequest` attaches
 * `X-Auth-Transport: cookie`. Unlike the body path it runs with an empty token store — that is how a
 * reloaded page gets its session back. Any failure (refusal or transport error) fires [onRefused]:
 * a browser session with no access token and no working cookie has nothing left to fall back on.
 */
private suspend fun RefreshTokensParams.refreshViaCookie(
    tokenStore: TokenStore,
    baseUrl: String,
    onRefused: () -> Unit,
    onStored: () -> Unit,
    logger: Logger,
    storageDispatcher: CoroutineDispatcher,
): BearerTokens? =
    try {
        val response = client.post(baseUrl + REFRESH_PATH) { markAsRefreshTokenRequest() }
        if (response.status == HttpStatusCode.OK) {
            val login = response.body<LoginResponse>()
            if (login.token.refreshToken != null) {
                // A stripped X-Auth-Transport header does not explain this: the server answers a
                // non-200 to a headerless /auth/refresh (handled by onRefused below, not this branch),
                // it never silently falls back to body transport. Reaching here means the SERVER put a
                // refresh token in a 200 JSON body while we asked for cookie transport — a
                // server-side contract violation, putting a refresh token into JS-readable JSON on
                // what was supposed to be a cookie-only session. Never store it; sign out instead.
                logger.log(
                    "Refresh answered 200 with a body refresh token under cookie transport — " +
                        "server-side contract violation; signing out",
                )
                onRefused()
                return null
            }
            withContext(storageDispatcher) { tokenStore.storeRefreshedSession(login) }
            onStored()
            BearerTokens(login.token.accessToken, null)
        } else {
            onRefused()
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onRefused()
        null
    }
