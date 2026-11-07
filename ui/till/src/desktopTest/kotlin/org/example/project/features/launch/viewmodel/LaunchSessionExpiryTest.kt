package org.example.project.features.launch.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.SettingManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.impl.KtorTerminalApiClient
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.HttpCall
import org.example.project.core.network.createPosHttpClient
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.viewmodels.LaunchCoordinator
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLaunchSetupApiClient
import org.example.project.testutils.fakes.FakeLaunchTerminalApiClient
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakePlatformNotificationService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NEW-1 (emulator pass 4, 2026-09-28): after a crash the till opened on "Server nicht erreichbar"
 * although pos-server was up — the launch's device lookup came back 401 `AUTH_SESSION_EXPIRED` and
 * [LaunchCoordinator] read every code outside its device-rejection set as "offline".
 *
 * The launch is driven here through the REAL shared HTTP client ([createPosHttpClient] — bearer
 * attachment, the refresh-on-401 path, `onUnauthorized`) and the real [KtorTerminalApiClient], over a
 * [MockEngine] that answers the way pos-server does: `RouteAuthorizationPlugin` refuses ANY request
 * carrying an expired bearer with 401 `AUTH_SESSION_EXPIRED` — even on `me-by-device`, whose own
 * authentication is the `X-Device-Token` — before the device-principal check runs, and the
 * device-principal answers an unknown device token with 401 `AUTH_INVALID_CREDENTIALS`.
 *
 * The session-reset wiring mirrors the app root: the client's `onUnauthorized` runs the lifecycle
 * handler's session reset, whose `onSessionReset` re-runs the launch (`AppNavigation` does the same
 * through `TerminalColdResetBus`, which only buffers the request).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchSessionExpiryTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        // LocalDatabaseManager publishes the device token process-wide; do not leak it to other tests.
        DeviceTokenProvider.token = null
    }

    /** pos-server's answers for the handful of routes the launch and a first screen touch. */
    private class FakeServer {
        /** The one access token the server still accepts; any other bearer has "expired". */
        var validAccess: String? = null
        var refreshAccepted = true
        var refreshCalls = 0
            private set

        /** The `Authorization` header each device lookup arrived with (null = none). */
        val lookupBearers = mutableListOf<String?>()

        fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath
            val bearer = request.headers[HttpHeaders.Authorization]
            return when {
                path.endsWith("/api/auth/refresh") -> {
                    refreshCalls++
                    if (refreshAccepted) {
                        validAccess = "access-2"
                        respond(REFRESH_OK, HttpStatusCode.OK, JSON)
                    } else {
                        problem(HttpStatusCode.Unauthorized, PosErrorCode.AUTH_SESSION_EXPIRED)
                    }
                }
                path.endsWith("/api/v1/terminals/me-by-device") -> {
                    lookupBearers += bearer
                    when {
                        bearer != null && bearer != "Bearer $validAccess" ->
                            problem(HttpStatusCode.Unauthorized, PosErrorCode.AUTH_SESSION_EXPIRED)
                        request.headers[DeviceIdentifiers.DEVICE_TOKEN_HEADER] != DEVICE_TOKEN ->
                            problem(HttpStatusCode.Unauthorized, PosErrorCode.AUTH_INVALID_CREDENTIALS)
                        else -> respond(TERMINAL_JSON, HttpStatusCode.OK, JSON)
                    }
                }
                // Any person-authenticated route, e.g. the sync Main runs on entry.
                else ->
                    if (bearer == "Bearer $validAccess") {
                        respond("[]", HttpStatusCode.OK, JSON)
                    } else {
                        problem(HttpStatusCode.Unauthorized, PosErrorCode.AUTH_SESSION_EXPIRED)
                    }
            }
        }

        private fun MockRequestHandleScope.problem(
            status: HttpStatusCode,
            code: PosErrorCode,
        ) = respond(
            """{"type":"about:blank","title":"x","status":${status.value},"code":"${code.name}","detail":"x"}""",
            status,
            headersOf(HttpHeaders.ContentType, "application/problem+json"),
        )
    }

    /**
     * The till as the app wires it, over the real client. [tokenStore] is created first and may be
     * used (e.g. by a sticky-restarted service) before [coordinator] builds the WIPE session.
     */
    private inner class Rig {
        val server = FakeServer()
        val db: LocalDatabaseManager = LocalDatabaseManager(FakeKeyValueStore()).apply { setDeviceToken(DEVICE_TOKEN) }
        val tokenStore: TokenStore = FakeTokenStore.create()
        val setup = FakeLaunchSetupApiClient()
        private var lifecycleRef: TerminalLifecycleHandler? = null

        val client: HttpClient =
            createPosHttpClient(
                engine =
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = this@LaunchSessionExpiryTest.dispatcher
                            addHandler { request -> with(server) { handle(request) } }
                        },
                    ),
                tokenStore = tokenStore,
                onUnauthorized = { lifecycleRef!!.sessionReset() },
                baseUrlResolver = { "http://pos/" },
                logger = SILENT,
                logLevel = LogLevel.NONE,
            )

        lateinit var session: SessionState
        lateinit var lifecycle: TerminalLifecycleHandler
        lateinit var coordinator: LaunchCoordinator

        /** Every destination the coordinator resolved, in order — to prove Offline never flashed. */
        val seen = mutableListOf<AppDestination>()

        /** Builds the WIPE session (what `AppNavigation` does on the first activity start) and the launch. */
        fun TestScope.launchApp(): LaunchCoordinator {
            session = SessionState(tokenStore, StartupPolicy.WIPE)
            val permissionManager = PermissionManager(db, tokenStore)
            lifecycle =
                TerminalLifecycleHandler(FakeNotificationConnection(), tokenStore, db, permissionManager, session) {
                    sweepCachedBearer(client)
                }
            lifecycleRef = lifecycle
            val httpCall =
                object : HttpCall {
                    override val httpClient = client

                    override fun getBaseUrl() = "http://pos/"
                }
            coordinator =
                LaunchCoordinator(
                    setup,
                    KtorTerminalApiClient(httpCall),
                    db,
                    tokenStore,
                    permissionManager,
                    session,
                    lifecycle,
                    SettingManager(FakeKeyValueStore()),
                    FakePlatformNotificationService(),
                    null,
                    this@LaunchSessionExpiryTest.dispatcher,
                )
            lifecycle.onSessionReset = { coordinator.onTerminalRoleChanged() }
            backgroundScope.launch(dispatcher) { coordinator.destination.collect { seen += it } }
            return coordinator
        }

        /** A person signed in on this till: what an in-process re-run of the launch meets. */
        fun signIn(access: String) {
            tokenStore.storeTokens(accessToken = access, refreshToken = "refresh-1", expiresIn = 900)
            session.markLoggedIn()
        }

        /** Mirrors the `AppNavigation` bridge from SessionState into the coordinator. */
        fun bridgeSession() = coordinator.setSessionStatus(session.status)
    }

    /**
     * [advanceUntilIdle] plus a real-time wait. The session reset drops Ktor's cached bearer while
     * the refused refresh still holds the provider's mutex, and Ktor then clears it on `GlobalScope`
     * — a real thread, whose unlock resumes the re-run's token load only after the virtual queue has
     * already drained. Bounded, and it returns as soon as [done] holds.
     */
    private suspend fun TestScope.settle(done: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (done()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        advanceUntilIdle()
    }

    // --- the NEW-1 reproduction -----------------------------------------------------------------

    /**
     * The exact field sequence: the sticky-restarted `PosNotificationService` makes the process's
     * first authenticated request while the stored token is still good, so Ktor's bearer provider
     * CACHES it. The token expires. The launcher then starts the activity, whose `SessionState`
     * WIPEs the token store — but not that cache — and the launch's device lookup went out with the
     * dead bearer. The server refused it with `AUTH_SESSION_EXPIRED`; the refresh found no refresh
     * token in the wiped store and gave up silently; the coordinator showed "server unreachable".
     */
    @Test
    fun a_bearer_cached_before_the_startup_wipe_does_not_make_a_reachable_server_look_offline() =
        runTest(dispatcher) {
            val rig = Rig()
            rig.tokenStore.storeTokens(accessToken = "access-mgr", refreshToken = "refresh-mgr", expiresIn = 900)
            rig.server.validAccess = "access-mgr"
            rig.client.get("http://pos/api/v1/notifications/stream-probe") // the service's request
            rig.server.validAccess = null // fifteen minutes later: the manager's token has expired

            val c = with(rig) { launchApp() }
            advanceUntilIdle()

            assertEquals(AppDestination.Login, c.destination.value, "the server is up: the till must ask for a login")
            assertFalse(rig.seen.any { it is AppDestination.Offline }, "never 'server unreachable': ${rig.seen}")
            assertEquals(DEVICE_TOKEN, rig.db.getDeviceToken(), "a person's expired session must not un-pair the till")
            assertEquals(listOf<String?>(null), rig.server.lookupBearers, "the device lookup must not carry a person's bearer")
        }

    // --- a live session meeting an expired access token -----------------------------------------

    /**
     * An in-process re-run of the launch (the offline screen's retry, a role change) while a person
     * is signed in and the access token has expired but the refresh token is good: the launch lands
     * in Main and the session goes on, renewed silently by the shared client on the next call.
     */
    @Test
    fun an_expired_access_token_with_a_valid_refresh_token_continues_the_session() =
        runTest(dispatcher) {
            val rig = Rig()
            val c = with(rig) { launchApp() }
            rig.signIn(access = "access-1")
            rig.bridgeSession()
            rig.server.validAccess = null // access-1 has expired; refresh-1 is still good
            advanceUntilIdle()

            c.refresh()
            advanceUntilIdle()
            assertEquals(AppDestination.Main, c.destination.value)

            // The first person call Main makes (its sync): the shared client refreshes and retries.
            val response = rig.client.get("http://pos/api/products")
            advanceUntilIdle()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, rig.server.refreshCalls, "exactly one silent refresh, by the shared client")
            assertEquals("access-2", rig.tokenStore.accessToken)
            assertEquals(SessionStatus.AUTHENTICATED, rig.session.status)
            assertEquals(AppDestination.Main, c.destination.value)
            assertFalse(rig.seen.any { it is AppDestination.Offline }, "never 'server unreachable': ${rig.seen}")
        }

    /**
     * Same, but the server refuses the refresh (session revoked or refresh token expired): the person
     * signs in again, the device stays paired, and the offline screen never shows.
     */
    @Test
    fun a_refused_refresh_goes_to_login_and_keeps_the_pairing() =
        runTest(dispatcher) {
            val rig = Rig()
            val c = with(rig) { launchApp() }
            rig.signIn(access = "access-1")
            rig.bridgeSession()
            rig.server.validAccess = null
            rig.server.refreshAccepted = false
            advanceUntilIdle()

            c.refresh()
            advanceUntilIdle()
            // Main's first call is what asks the server about the session.
            rig.client.get("http://pos/api/products")
            rig.bridgeSession()
            settle { c.destination.value == AppDestination.Login }

            assertEquals(AppDestination.Login, c.destination.value)
            assertEquals(SessionStatus.UNAUTHENTICATED, rig.session.status)
            assertNull(rig.tokenStore.refreshToken, "the refused session is gone")
            assertEquals(DEVICE_TOKEN, rig.db.getDeviceToken(), "session != pairing: the device stays paired")
            assertFalse(rig.seen.any { it is AppDestination.Offline }, "never 'server unreachable': ${rig.seen}")
        }

    /**
     * The device really is rejected, and a person's dead bearer is riding along. The server's
     * bearer check runs first, so the lookup used to come back `AUTH_SESSION_EXPIRED` — a session
     * code hiding the device's own verdict. Answered on the device credential alone, the rejection
     * reaches the coordinator and the device returns to pairing (the existing cold-reset semantics).
     */
    @Test
    fun a_rejected_device_token_returns_to_pairing_even_with_a_dead_person_bearer() =
        runTest(dispatcher) {
            val rig = Rig()
            val c = with(rig) { launchApp() }
            rig.signIn(access = "access-1")
            rig.bridgeSession()
            rig.server.validAccess = null
            rig.server.refreshAccepted = false
            rig.db.setDeviceToken("revoked-token")
            advanceUntilIdle()

            c.refresh()
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank(), "a rejected device token is cleared")
        }

    // --- the coordinator's classification, over a fake client -----------------------------------

    private inner class FakeRig {
        val db: LocalDatabaseManager = LocalDatabaseManager(FakeKeyValueStore()).apply { setDeviceToken(DEVICE_TOKEN) }
        val tokenStore: TokenStore = FakeTokenStore.create()
        val session = SessionState(tokenStore, StartupPolicy.WIPE)
        val permissionManager = PermissionManager(db, tokenStore)
        val lifecycle = TerminalLifecycleHandler(FakeNotificationConnection(), tokenStore, db, permissionManager, session) { }
        val term = FakeLaunchTerminalApiClient()
        val seen = mutableListOf<AppDestination>()

        fun TestScope.coordinator(): LaunchCoordinator {
            val c =
                LaunchCoordinator(
                    FakeLaunchSetupApiClient(),
                    term,
                    db,
                    tokenStore,
                    permissionManager,
                    session,
                    lifecycle,
                    SettingManager(FakeKeyValueStore()),
                    FakePlatformNotificationService(),
                    null,
                    this@LaunchSessionExpiryTest.dispatcher,
                )
            lifecycle.onSessionReset = { c.onTerminalRoleChanged() }
            backgroundScope.launch(dispatcher) { c.destination.collect { seen += it } }
            return c
        }
    }

    /**
     * `AUTH_SESSION_EXPIRED` names a PERSON's session, never the device: the device lookup is
     * authenticated by the device token. Whatever produced one, it must neither read as "server
     * unreachable" (the server just answered) nor un-pair the till. It ends the person's session —
     * the same session reset a refused refresh takes — and the launch re-runs to the login screen.
     */
    @Test
    fun a_session_expiry_code_on_the_device_lookup_is_neither_offline_nor_an_unpair() =
        runTest(dispatcher) {
            val rig = FakeRig()
            var lookups = 0
            rig.term.terminalByDeviceResult = {
                lookups++
                if (lookups == 1) throw PosApiException(PosErrorCode.AUTH_SESSION_EXPIRED, 401, "expired", null)
                terminal()
            }
            val c = with(rig) { coordinator() }
            advanceUntilIdle()

            assertEquals(AppDestination.Login, c.destination.value)
            assertEquals(DEVICE_TOKEN, rig.db.getDeviceToken(), "session != pairing")
            assertFalse(rig.seen.any { it is AppDestination.Offline }, "never 'server unreachable': ${rig.seen}")
        }

    @Test
    fun a_device_rejection_code_still_returns_to_pairing() =
        runTest(dispatcher) {
            val rig = FakeRig()
            rig.term.terminalByDeviceResult = {
                throw PosApiException(PosErrorCode.TERMINAL_NEEDS_PAIRING, 409, "unpaired", null)
            }
            val c = with(rig) { coordinator() }
            advanceUntilIdle()

            assertEquals(AppDestination.Pairing, c.destination.value)
            assertTrue(rig.db.getDeviceToken().isNullOrBlank())
        }

    @Test
    fun a_transport_failure_on_the_device_lookup_is_offline_and_keeps_the_pairing() =
        runTest(dispatcher) {
            val rig = FakeRig()
            rig.term.terminalByDeviceResult = { throw NetworkApiException(RuntimeException("timeout")) }
            val c = with(rig) { coordinator() }
            advanceUntilIdle()

            assertEquals(AppDestination.Offline(canChangeServerAddress = false), c.destination.value)
            assertEquals(DEVICE_TOKEN, rig.db.getDeviceToken())
        }

    @Test
    fun a_server_fault_on_the_device_lookup_is_offline_and_keeps_the_pairing() =
        runTest(dispatcher) {
            val rig = FakeRig()
            rig.term.terminalByDeviceResult = {
                throw PosApiException(PosErrorCode.INTERNAL_ERROR, 503, "unavailable", null)
            }
            val c = with(rig) { coordinator() }
            advanceUntilIdle()

            assertEquals(AppDestination.Offline(canChangeServerAddress = false), c.destination.value)
            assertEquals(DEVICE_TOKEN, rig.db.getDeviceToken())
        }

    private companion object {
        const val DEVICE_TOKEN = "device-token-1"

        val SILENT =
            object : Logger {
                override fun log(message: String) = Unit
            }

        val JSON = headersOf(HttpHeaders.ContentType, "application/json")

        fun terminal() =
            TerminalDto.TerminalResponseDto(
                id = 7L,
                deviceId = "dev-1",
                terminalId = "TER-001",
                terminalBrand = "A",
                terminalModel = "Tab",
                terminalSerialNr = null,
                terminalSwBrand = "POS",
                terminalSwVersion = "1.0",
                isActive = true,
                tseClientRegistered = false,
                registeredAt = "t",
                lastSeenAt = "t",
                role = "CASHIER",
                fiscal = true,
            )

        val TERMINAL_JSON: String = Json.encodeToString(TerminalDto.TerminalResponseDto.serializer(), terminal())

        const val REFRESH_OK =
            """{"token":{"accessToken":"access-2","refreshToken":"refresh-2","expiresIn":900},""" +
                """"employee":{"id":"1","username":"cas1","employeeNumber":"E1","firstName":"C","lastName":"One",""" +
                """"role":"CASHIER","permissions":[],"language":"de"}}"""

        /** What the lifecycle handler's `clearCachedAuth` does in production (`viewModelModule`). */
        fun sweepCachedBearer(client: HttpClient) {
            client.authProviders
                .filterIsInstance<BearerAuthProvider>()
                .forEach { it.clearToken() }
        }
    }
}
