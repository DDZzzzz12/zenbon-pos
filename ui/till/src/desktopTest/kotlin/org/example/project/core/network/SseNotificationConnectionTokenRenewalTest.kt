package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.installDeviceTokenLoader
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.impl.KtorAuthApiClient
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.utils.platformIoDispatcher
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.IoMarkingDispatcher
import org.example.project.testutils.fakes.SecretAccessRecorder
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import io.ktor.client.plugins.logging.Logger as KtorLogger

/**
 * Token renewal on the SSE connect path, over the REAL HTTP client (shared `createPosHttpClient`,
 * Ktor `Auth` plugin) and the REAL `KtorAuthApiClient` — the two collaborators the other SSE tests
 * replace with fakes, and the two that carry the renewal.
 *
 * The defect this pins: `TokenStore.isAccessTokenExpired()` answers a *renewal* question — "does
 * this token expire within the 5-minute buffer" — not "is this token unusable". A login-less
 * ORDER_STATION whose access token lives one minute (`JWT_ACCESS_TOKEN_EXPIRY_MINUTES=1`) is
 * therefore "expired" for its entire life, and stays "expired" the instant after a successful
 * device-token re-exchange. `ensureFreshToken()` re-asked that question after a *successful*
 * `/api/auth/validate` and reported an authentication failure, which the NotificationViewModel
 * turns into a blocking dialog and MainScreen into a logout.
 */
@OptIn(ExperimentalTime::class)
class SseNotificationConnectionTokenRenewalTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val unauthorizedHeaders = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=\"pos\"")

    private val silentLogger =
        object : KtorLogger {
            override fun log(message: String) = Unit
        }

    @AfterEach
    fun tearDown() {
        DeviceTokenProvider.reset()
    }

    private class NoopApprovalClient : ApprovalRequestApiClient {
        override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> = emptyList()

        override suspend fun approveRequest(requestId: String): AuthDto.ApprovalResponseDto =
            AuthDto.ApprovalResponseDto(requestId = requestId, approved = true, approvingManagerId = "")

        override suspend fun denyRequest(
            requestId: String,
            reason: String,
        ): AuthDto.ApprovalResponseDto = AuthDto.ApprovalResponseDto(requestId = requestId, approved = false, approvingManagerId = "")

        override suspend fun cancelRequest(requestId: String) {}

        override suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto =
            AuthDto.RequestStatusDto(requestId = requestId, status = AuthDto.RequestStatus.PENDING)
    }

    private class NoopBillingClient : BillingRequestApiClient {
        override suspend fun getPendingBillingRequests(): List<NotificationDto.BillingRequestNotificationDto> = emptyList()
    }

    private class NoopChangeFeedApiClient : ChangeFeedApiClient {
        override suspend fun getChanges(
            since: Long,
            limit: Int,
        ): ChangeFeedDto.ResponseDto = ChangeFeedDto.ResponseDto(entries = emptyList(), highWaterSeq = 0L, minAvailableSeq = 0L)
    }

    /** What the server answers a station's exchange with: a new token, one minute of life, no refresh token. */
    private fun stationSessionJson(accessToken: String) =
        """
        {"accessToken":"$accessToken","expiresAt":"${Clock.System.now() + 60.seconds}",
         "terminalId":"TER-007","role":"ORDER_STATION","capabilities":["ORDER_READ","ORDER_WRITE"]}
        """.trimIndent()

    /** A paired station: an access token, NO refresh token, and [secondsOfLifeLeft] until it expires. */
    private fun stationStore(secondsOfLifeLeft: Long): TokenStore =
        FakeTokenStore.create().apply {
            accessToken = "station-1"
            refreshToken = null
            stationRole = "ORDER_STATION"
            expiresAt = Clock.System.now().epochSeconds + secondsOfLifeLeft
        }

    /** The connection over the real client; both read the token store on [io], or on the production default when null. */
    private fun connection(
        tokenStore: TokenStore,
        engine: MockEngine,
        io: CoroutineDispatcher? = null,
        onUnauthorized: () -> Unit = {},
    ): SseNotificationConnection {
        // The device token reaches the exchange as it does in production: the database definition installs the
        // manager as DeviceTokenProvider's loader, and the first request loads the persisted token through it.
        val localDb =
            FakeLocalDatabaseManager.create(
                mapOf("api_base_url" to "http://localhost:8080/", "device_token" to "dev-tok"),
            )
        installDeviceTokenLoader(localDb)
        val storage = io ?: platformIoDispatcher()
        val client =
            createPosHttpClient(
                engine = engine,
                tokenStore = tokenStore,
                onUnauthorized = onUnauthorized,
                baseUrlResolver = localDb::getApiBaseUrl,
                logger = silentLogger,
                logLevel = LogLevel.NONE,
                storageDispatcher = storage,
            )
        val reconciler =
            NotificationReconciler(
                syncService = FakeSyncService(),
                changeFeedApiClient = NoopChangeFeedApiClient(),
                localDatabaseManager = localDb,
                approvalApiClient = NoopApprovalClient(),
                billingApiClient = NoopBillingClient(),
                rotationApiClient =
                    org.example.project.testutils.fakes
                        .NoopRotationApiClient(),
                permissionManager = FakePermissionManager.create(localDb, tokenStore),
            )
        return SseNotificationConnection(
            client = client,
            tokenStore = tokenStore,
            authApiClient = KtorAuthApiClient(LocalDbHttpCall(client, localDb), client),
            localDb = localDb,
            reconciler = reconciler,
            reconnectPolicy = ReconnectPolicy(),
            ioDispatcher = storage,
        )
    }

    private fun SseNotificationConnection.authFailures() =
        errors.replayCache.filterIsInstance<NotificationConnection.NotificationConnectionError.AuthenticationFailed>()

    /**
     * E2E-2026-09-19: restarting pos-server under a running till signed the operator out. The
     * renewal threw a ConnectException, `ensureFreshToken` reported an authentication failure, and
     * `MainScreen` logs out on one. An unreachable server must produce a reconnect instead.
     */
    @Test
    fun `an unreachable server during renewal is not an authentication failure`() =
        runTest {
            val store = stationStore(secondsOfLifeLeft = -60)
            var signedOut = false
            val engine = MockEngine { throw IOException("connection refused") }

            val sse = connection(store, engine, onUnauthorized = { signedOut = true })
            sse.connect("api/notifications/stream")
            sse.dispose()

            assertEquals(emptyList(), sse.authFailures(), "an outage is not a rejected session")
            assertFalse(signedOut, "and must not sign the operator out")
        }

    @Test
    fun `connect reads the access token on the IO dispatcher`() =
        runTest {
            val io = IoMarkingDispatcher(StandardTestDispatcher(testScheduler))
            val recorder = SecretAccessRecorder(FakeKeyValueStore(), io)
            // No stored expiry, so the freshness check has to decode the access token itself — a second sealed read.
            val store =
                TokenStore(recorder).apply {
                    accessToken = "station-1"
                    stationRole = "ORDER_STATION"
                }
            recorder.reset()
            val engine = MockEngine { respond("""{"valid":true}""", HttpStatusCode.OK, jsonHeaders) }
            val sse = connection(store, engine, io)

            sse.connect("api/notifications/stream")
            sse.dispose()

            assertTrue(
                "get:jwt_access_token" in recorder.secretAccesses,
                "expected the connect path to read the access token, got ${recorder.secretAccesses}",
            )
            assertEquals(emptyList(), recorder.offIo, "the access token was opened on the caller's thread")
        }

    @Test
    fun `a station inside the renewal buffer is not an authentication failure`() =
        runTest {
            // 45 seconds of life left: well inside the 300-second renewal buffer, so
            // isAccessTokenExpired() is true — but the server still accepts the token, so there is
            // nothing to renew and nothing has failed.
            var exchanges = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/terminals/session") -> {
                            exchanges++
                            respond(stationSessionJson("station-2"), HttpStatusCode.OK, jsonHeaders)
                        }
                        else -> respond("""{"valid":true}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val sse = connection(stationStore(secondsOfLifeLeft = 45), engine)

            sse.connect("api/notifications/stream")
            sse.dispose()

            assertEquals(emptyList(), sse.authFailures(), "a token the server accepts is not an auth failure")
            assertEquals(0, exchanges, "nothing to renew: the token is still valid")
        }

    @Test
    fun `an expired station token is renewed silently by re-exchanging the device token`() =
        runTest {
            var exchanges = 0
            var deviceTokenSeen: String? = null
            var signedOut = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/terminals/session") -> {
                            exchanges++
                            deviceTokenSeen = request.headers["X-Device-Token"]
                            respond(stationSessionJson("station-2"), HttpStatusCode.OK, jsonHeaders)
                        }
                        request.headers[HttpHeaders.Authorization] == "Bearer station-2" ->
                            respond("""{"valid":true}""", HttpStatusCode.OK, jsonHeaders)
                        else -> respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val store = stationStore(secondsOfLifeLeft = -30)
            val sse = connection(store, engine) { signedOut++ }

            sse.connect("api/notifications/stream")
            sse.dispose()

            assertEquals(1, exchanges, "the expired token is re-exchanged exactly once")
            assertEquals("dev-tok", deviceTokenSeen, "the exchange authenticates with the device token")
            assertEquals("station-2", store.accessToken, "the renewed token is stored")
            assertEquals(0, signedOut, "a renewed station is not cold reset")
            assertEquals(
                emptyList(),
                sse.authFailures(),
                "the renewal succeeded — the fresh token is still inside the 300s buffer, which is not a failure",
            )
        }

    @Test
    fun `a refused exchange stays an authentication failure and cold resets`() =
        runTest {
            var exchanges = 0
            var signedOut = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/terminals/session")) {
                        exchanges++
                        respond("""{"detail":"Device not paired"}""", HttpStatusCode.Conflict, jsonHeaders)
                    } else {
                        respond("", HttpStatusCode.Unauthorized, unauthorizedHeaders)
                    }
                }
            val sse = connection(stationStore(secondsOfLifeLeft = -30), engine) { signedOut++ }

            sse.connect("api/notifications/stream")
            sse.dispose()

            assertEquals(1, exchanges)
            assertEquals(1, signedOut, "409 means revoked: the cold reset must still fire")
            assertTrue(sse.authFailures().isNotEmpty(), "an unrenewable session is an authentication failure")
        }

    @Test
    fun `a transport failure is reported as a connection failure, not an auth failure`() =
        runTest {
            var signedOut = 0
            val engine = MockEngine { throw RuntimeException("connection refused") }
            val sse = connection(stationStore(secondsOfLifeLeft = -30), engine) { signedOut++ }

            sse.connect("api/notifications/stream")
            // The ConnectionFailed is emitted from the reconnect scheduler's own coroutine on
            // Dispatchers.Default, which runTest's virtual clock does not advance — so wait for it
            // in real time rather than racing dispose().
            val sawConnectionFailure =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeoutOrNull(5_000) {
                        while (sse.errors.replayCache.none {
                                it is NotificationConnection.NotificationConnectionError.ConnectionFailed
                            }
                        ) {
                            delay(5)
                        }
                        true
                    } ?: false
                }
            sse.dispose()

            // This test used to assert the opposite — that the failure surfaced as an
            // AuthenticationFailed, "so the caller knows". The caller does have to know, but
            // NotificationViewModel turns an AuthenticationFailed into `authError`, and MainScreen
            // logs out on that: restarting pos-server signed the operator out (E2E-2026-09-19).
            // A ConnectionFailed tells the caller without ending anyone's session.
            assertEquals(emptyList(), sse.authFailures(), "an outage is not a rejected session")
            assertTrue(sawConnectionFailure, "the stream cannot open, and the caller has to know")
            assertFalse(signedOut > 0, "a network blip is not a revocation")
        }
}
