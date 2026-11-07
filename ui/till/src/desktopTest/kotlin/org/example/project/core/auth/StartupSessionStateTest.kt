package org.example.project.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.network.NotificationConnection
import org.example.project.core.network.NotificationReconciler
import org.example.project.core.network.ReconnectPolicy
import org.example.project.core.network.SseNotificationConnection
import org.example.project.core.network.createPosHttpClient
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cashier terminal's startup WIPE must also drop the bearer Ktor's Auth provider has cached.
 *
 * Field sequence (emulator pass 4, after NEW-1): Android restarts the crashed `PosNotificationService`
 * alone in a new process; its stream connect makes the process's first authenticated request, so the
 * bearer provider caches the stored (previous person's) token. The launcher then starts the activity,
 * whose `SessionState` wipes the token store — and, before [startupSessionState], not the cache, so
 * every request up to the next login still went out as that previous person.
 *
 * Driven through the real [createPosHttpClient], because the cache being tested is its Auth provider's.
 */
@OptIn(InternalAPI::class, ExperimentalEncodingApi::class)
class StartupSessionStateTest {
    /** Records each request's `Authorization`; serves an endless event stream on the SSE path, `{}` elsewhere. */
    private class RecordingEngine : HttpClientEngineBase("startup-wipe-stub") {
        val authorizations = CopyOnWriteArrayList<Pair<String, String?>>()

        override val config = object : io.ktor.client.engine.HttpClientEngineConfig() {}

        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(SSECapability, HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            authorizations.add(data.url.encodedPath to data.headers[HttpHeaders.Authorization])
            val callContext = callContext()
            if (!data.url.encodedPath.endsWith("/stream")) {
                val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                return HttpResponseData(
                    HttpStatusCode.OK,
                    GMTDate(),
                    headers,
                    HttpProtocolVersion.HTTP_1_1,
                    ByteReadChannel("{}"),
                    callContext,
                )
            }
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            val body = ByteChannel()
            val adapted: Any =
                data.attributes
                    .getOrNull(ResponseAdapterAttributeKey)
                    ?.adapt(data, HttpStatusCode.OK, headers, body, data.body, callContext)
                    ?: body
            return HttpResponseData(HttpStatusCode.OK, GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, adapted, callContext)
        }

        fun authorizationsFor(pathSuffix: String) = authorizations.filter { it.first.endsWith(pathSuffix) }.map { it.second }
    }

    private fun clientOver(
        engine: RecordingEngine,
        tokenStore: TokenStore,
    ): HttpClient =
        createPosHttpClient(
            engine = engine,
            tokenStore = tokenStore,
            onUnauthorized = { },
            baseUrlResolver = { "http://localhost/" },
            logger =
                object : Logger {
                    override fun log(message: String) = Unit
                },
            logLevel = LogLevel.NONE,
        ) {
            install(SSE)
            install(HttpTimeout)
        }

    @Test
    fun a_request_after_the_startup_wipe_does_not_carry_the_bearer_cached_before_it() =
        runTest {
            val engine = RecordingEngine()
            val tokenStore = FakeTokenStore.create()
            tokenStore.storeTokens("manager-access", "manager-refresh", expiresIn = 3600)
            val client = clientOver(engine, tokenStore)
            client.get("http://localhost/api/products") // the revived service's request: caches the bearer

            val session = startupSessionState(tokenStore, client, FakeNotificationConnection())
            client.get("http://localhost/api/products") // any request before the next login

            assertEquals(SessionStatus.UNAUTHENTICATED, session.status)
            assertNull(tokenStore.accessToken, "precondition: the wipe emptied the store")
            assertEquals(
                listOf<String?>("Bearer manager-access", null),
                engine.authorizationsFor("/api/products"),
                "the request after the wipe must go out without the previous person's bearer",
            )
        }

    /**
     * Composes with the stream re-bind (NOTE-4): `SseNotificationConnection` sets `Authorization` from
     * the token store itself, but the Auth provider replaces any existing header with its cached
     * bearer. Without the sweep a re-bound stream therefore still went out as the previous person.
     * The next person's token is written straight to the store here — not through `login()`, which
     * sweeps on its own — so only the startup sweep stands between the stream and the stale bearer.
     */
    @Test
    fun a_stream_re_bound_after_the_startup_wipe_uses_the_current_token_not_the_cached_one() =
        runTest {
            val engine = RecordingEngine()
            val tokenStore = FakeTokenStore.create()
            val manager = jwtFor("7", "M001")
            tokenStore.storeTokens(manager, "refresh-m", expiresIn = 3600)
            val client = clientOver(engine, tokenStore)
            val connection = connectionFor(client, tokenStore)

            // The crash-restarted service opens the stream for the stored session.
            connection.connect("api/notifications/stream")
            connection.awaitConnected()

            startupSessionState(tokenStore, client, connection)
            val cashier = jwtFor("3", "K001")
            tokenStore.storeTokens(cashier, "refresh-k", expiresIn = 3600)
            connection.connect("api/notifications/stream")
            awaitStreamRequests(engine, 2)
            connection.disconnect()

            assertEquals(listOf<String?>("Bearer $manager", "Bearer $cashier"), engine.authorizationsFor("/stream"))
        }

    /**
     * NOTE-8 (emulator pass 5): the crash-revived service's stream stayed open for the previous person after the
     * wipe, until the next login re-bound it. A billing request addressed to them arrived on a till where nobody
     * was logged in, and nobody saw it. The wipe must end that stream, and it must stay ended — no reconnect with
     * nobody logged in.
     */
    @Test
    fun the_startup_wipe_closes_a_stream_opened_with_the_stored_session() =
        runTest {
            val engine = RecordingEngine()
            val tokenStore = FakeTokenStore.create()
            tokenStore.storeTokens(jwtFor("7", "M001"), "refresh-m", expiresIn = 3600)
            val client = clientOver(engine, tokenStore)
            val connection = connectionFor(client, tokenStore)
            connection.connect("api/notifications/stream")
            connection.awaitConnected()

            startupSessionState(tokenStore, client, connection)

            connection.awaitState(NotificationConnection.ConnectionState.Disconnected)
            awaitStreamRequests(engine, 1)
            assertEquals(1, engine.authorizationsFor("/stream").size, "nobody is logged in: the stream must not reconnect")
            assertEquals(NotificationConnection.ConnectionState.Disconnected, connection.connectionState.value)
        }

    /** After the wipe closed the stale stream, the next login's connect opens one with that person's token. */
    @Test
    fun a_login_after_the_wipe_connects_with_the_current_token() =
        runTest {
            val engine = RecordingEngine()
            val tokenStore = FakeTokenStore.create()
            val manager = jwtFor("7", "M001")
            tokenStore.storeTokens(manager, "refresh-m", expiresIn = 3600)
            val client = clientOver(engine, tokenStore)
            val connection = connectionFor(client, tokenStore)
            connection.connect("api/notifications/stream")
            connection.awaitConnected()
            startupSessionState(tokenStore, client, connection)
            connection.awaitState(NotificationConnection.ConnectionState.Disconnected)

            val cashier = jwtFor("3", "K001")
            tokenStore.storeTokens(cashier, "refresh-k", expiresIn = 3600)
            connection.connect("api/notifications/stream")
            connection.awaitConnected()
            awaitStreamRequests(engine, 2)
            connection.disconnect()

            assertEquals(listOf<String?>("Bearer $manager", "Bearer $cashier"), engine.authorizationsFor("/stream"))
        }

    /**
     * The close is asynchronous, so it checks ownership when it runs: a stream of the session that is stored — a
     * login that got there first — is left alone.
     */
    @Test
    fun closing_an_ended_session_leaves_the_current_session_s_stream_open() =
        runTest {
            val engine = RecordingEngine()
            val tokenStore = FakeTokenStore.create()
            tokenStore.storeTokens(jwtFor("3", "K001"), "refresh-k", expiresIn = 3600)
            val client = clientOver(engine, tokenStore)
            val connection = connectionFor(client, tokenStore)
            connection.connect("api/notifications/stream")
            connection.awaitConnected()

            connection.closeIfSessionEnded()
            awaitStreamRequests(engine, 1)

            assertEquals(NotificationConnection.ConnectionState.Connected, connection.connectionState.value)
            assertEquals(1, engine.authorizationsFor("/stream").size)
            connection.disconnect()
        }

    private fun connectionFor(
        client: HttpClient,
        tokenStore: TokenStore,
    ): SseNotificationConnection {
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost"))
        return SseNotificationConnection(
            client = client,
            tokenStore = tokenStore,
            authApiClient = FakeAuthApiClient(),
            localDb = localDb,
            reconciler =
                NotificationReconciler(
                    syncService = FakeSyncService(),
                    changeFeedApiClient = NoopChangeFeedApiClient(),
                    localDatabaseManager = localDb,
                    approvalApiClient = NoopApprovalClient(),
                    billingApiClient = NoopBillingClient(),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = FakePermissionManager.create(),
                ),
            reconnectPolicy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1),
            appVersion = "0.0.0-test",
        )
    }

    private suspend fun SseNotificationConnection.awaitConnected() = awaitState(NotificationConnection.ConnectionState.Connected)

    private suspend fun SseNotificationConnection.awaitState(state: NotificationConnection.ConnectionState) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { connectionState.first { it == state } }
        }

    /** Waits (real time) for [count] stream requests, then a little longer so a spurious extra one would show. */
    private suspend fun awaitStreamRequests(
        engine: RecordingEngine,
        count: Int,
    ) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (engine.authorizationsFor("/stream").size < count) delay(10) }
        delay(200)
    }

    /** An unsigned JWT the client-side decoder accepts. */
    private fun jwtFor(
        employeeId: String,
        employeeNumber: String,
    ): String {
        fun part(json: String) = Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        return "${part("""{"alg":"none"}""")}.${part("""{"sub":"$employeeId","employeeNumber":"$employeeNumber"}""")}.sig"
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
}
