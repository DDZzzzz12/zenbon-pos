package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for gap-free SSE replay: after the client observes an event that
 * carries an `id`, the next (re)connect must send that id back in the `Last-Event-ID`
 * request header so the server can resume the stream from where it left off.
 *
 * ## Why not `MockEngine`?
 *
 * `MockEngine` does NOT advertise [SSECapability] and never runs the SSE
 * [io.ktor.client.request.ResponseAdapter], so the Ktor SSE plugin fails fast with
 * `SSEClientException: Engine doesn't support SSECapability` before a request is even
 * issued. This test therefore uses a minimal custom [HttpClientEngineBase] ([SseStubEngine])
 * that mimics what a real engine (CIO/OkHttp) does for SSE:
 *  - declares [SSECapability] (+ [HttpTimeoutCapability], which the production code uses),
 *  - records each request's `Last-Event-ID` header into a shared list, and
 *  - returns `200 OK` with `Content-Type: text/event-stream` and a **finite** body
 *    containing exactly one event — `"id: 7\ndata: <json>\n\n"` — adapted into the SSE
 *    session via the request's [ResponseAdapterAttributeKey] (exactly as CIO's
 *    `readResponse` does).
 *
 * Because the body is finite, `session.incoming.collect { }` completes once the single
 * event is consumed — the normal "server closed the stream" path, which makes
 * [SseNotificationConnection] schedule a reconnect (1ms backoff here). The first event's
 * `id` ("7") updates the connection's `lastEventId`, so the reconnect GET carries
 * `Last-Event-ID: 7`.
 *
 * ## Timing
 *
 * [SseNotificationConnection] drives its stream/reconnect loop on an internal
 * `CoroutineScope(Dispatchers.Default)`, which `runTest`'s virtual clock cannot control.
 * The test awaits the second request on a bounded real-time basis (1ms backoff keeps this
 * fast and deterministic) and calls `disconnect()` before asserting to stop further
 * reconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalAPI::class)
class SseNotificationConnectionLastEventIdTest {
    /** Minimal SSE-capable engine that serves one finite event per request. */
    private class SseStubEngine(
        private val eventBody: String,
        private val recordedLastEventIds: CopyOnWriteArrayList<String?>,
        private val recordedAppVersions: CopyOnWriteArrayList<String?> = CopyOnWriteArrayList(),
        // Overrides `eventBody` for a request whose host maps to a non-null body. Lets one engine
        // stand in for two different servers on the SAME connection instance — e.g. a base-URL
        // change mid-process — without needing a second HttpClient/engine.
        private val eventBodyForHost: (String) -> String? = { null },
        // Called as each request arrives, so a test can await one instead of polling the recorded lists.
        private val onRequest: (HttpRequestData) -> Unit = {},
    ) : HttpClientEngineBase("sse-stub") {
        override val config = object : io.ktor.client.engine.HttpClientEngineConfig() {}

        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
            setOf(SSECapability, HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            onRequest(data)
            recordedLastEventIds.add(data.headers[HttpHeaders.LastEventID])
            recordedAppVersions.add(data.headers[DeviceIdentifiers.APP_VERSION_HEADER])

            val status = HttpStatusCode.OK
            val responseHeaders =
                headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Text.EventStream.toString(),
                )
            val rawBody = ByteReadChannel(eventBodyForHost(data.url.host) ?: eventBody)
            val callContext = callContext()

            // Mirror CIO's readResponse: run the SSE response adapter if the plugin set one.
            val responseBody: Any =
                data.attributes
                    .getOrNull(ResponseAdapterAttributeKey)
                    ?.adapt(data, status, responseHeaders, rawBody, data.body, callContext)
                    ?: rawBody

            return HttpResponseData(
                statusCode = status,
                requestTime = GMTDate(),
                headers = responseHeaders,
                version = HttpProtocolVersion.HTTP_1_1,
                body = responseBody,
                callContext = callContext,
            )
        }
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

    private fun singleEventBody(id: String = "7"): String {
        val serverJson = Json { encodeDefaults = true }
        val serverEncoded =
            serverJson.encodeToString<NotificationDto.NotificationMessage>(
                NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = "hi"),
            )
        return "id: $id\ndata: $serverEncoded\n\n"
    }

    private suspend fun newConnection(
        engine: SseStubEngine,
        appVersion: String = "0.0.0-test",
        localDb: LocalDatabaseManager = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost")),
        authApiClient: AuthApiClient = FakeAuthApiClient(),
        // 0 puts the token inside the renewal buffer, so connect() asks authApiClient to validate it first.
        tokenExpiresInSeconds: Long = 3600,
    ): SseNotificationConnection {
        val client =
            HttpClient(engine) {
                install(SSE)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout)
            }

        val tokenStore = FakeTokenStore.create()
        // Seed a token so ensureFreshToken()/accessToken succeed; non-expired unless the test asks otherwise.
        tokenStore.storeTokens("access-token", "refresh-token", expiresIn = tokenExpiresInSeconds)

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
                permissionManager = FakePermissionManager.create(),
            )
        return SseNotificationConnection(
            client = client,
            tokenStore = tokenStore,
            authApiClient = authApiClient,
            localDb = localDb,
            reconciler = reconciler,
            reconnectPolicy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1),
            appVersion = appVersion,
        )
    }

    /**
     * Awaits (real time) until at least two requests have been issued: the initial connect plus one
     * reconnect. runTest uses a virtual clock, but the connection runs on Dispatchers.Default —
     * switch to a real dispatcher so this poll measures wall-clock time and observes the reconnect.
     */
    private suspend fun awaitReconnect(recorded: List<*>) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (recorded.size < 2) {
                    delay(10)
                }
            }
        }
    }

    @Test
    fun reconnectAfterEvent_carriesLastEventIdHeader() =
        runTest {
            // Thread-safe — the engine runs on the connection's Dispatchers.Default scope.
            val recordedLastEventIds = CopyOnWriteArrayList<String?>()
            val connection = newConnection(SseStubEngine(singleEventBody(), recordedLastEventIds))

            connection.connect("api/notifications/stream")
            awaitReconnect(recordedLastEventIds)
            connection.disconnect()

            val snapshot = recordedLastEventIds.toList()
            assertTrue(snapshot.size >= 2, "expected at least 2 requests, got ${snapshot.size}: $snapshot")
            assertNull(snapshot.first(), "first connect must not send a Last-Event-ID")
            assertTrue(
                snapshot.drop(1).any { it == "7" },
                "a reconnect after the id=7 event must carry Last-Event-ID: 7, got $snapshot",
            )
        }

    @Test
    fun everyConnect_reportsTheAppVersionHeader() =
        runTest {
            // The server refreshes the terminal's DSFinV-K software version from this header, so
            // the initial connect and every reconnect must carry it.
            val recordedAppVersions = CopyOnWriteArrayList<String?>()
            val connection =
                newConnection(
                    SseStubEngine(singleEventBody(), CopyOnWriteArrayList(), recordedAppVersions),
                    appVersion = "1.1.0",
                )

            connection.connect("api/notifications/stream")
            awaitReconnect(recordedAppVersions)
            connection.disconnect()

            val snapshot = recordedAppVersions.toList()
            assertTrue(snapshot.size >= 2, "expected at least 2 requests, got ${snapshot.size}: $snapshot")
            assertTrue(snapshot.all { it == "1.1.0" }, "every connect must carry X-App-Version: 1.1.0, got $snapshot")
        }

    @Test
    fun tseStatusEvent_isRetainedAsTheLatestTseStatus() =
        runTest {
            // The server sends TSE status only on change and once per new stream. A consumer that
            // subscribes after that push must still be able to read it.
            val serverJson = Json { encodeDefaults = true }
            val encoded =
                serverJson.encodeToString<NotificationDto.NotificationMessage>(
                    NotificationDto.TseStatusUpdateDto(timestamp = "t", connected = true, healthy = true, mode = "DISABLED"),
                )
            val recorded = CopyOnWriteArrayList<String?>()
            val connection = newConnection(SseStubEngine("id: 9\ndata: $encoded\n\n", recorded))
            assertNull(connection.latestTseStatus.value)

            connection.connect("api/notifications/stream")
            // The event is parsed before the finite body ends, and the reconnect follows the end.
            awaitReconnect(recorded)
            connection.disconnect()

            assertEquals("DISABLED", connection.latestTseStatus.value?.mode)
        }

    @Test
    fun connectingToADifferentServer_clearsARetainedTseStatus() =
        runTest {
            // Server A pushes a TSE status once per stream and retains it, as above. The base URL
            // then changes mid-process (pairing screen / LaunchCoordinator server-address change),
            // and the new server's stream never sends a status at all — the footer must not keep
            // showing server A's stale state.
            val serverJson = Json { encodeDefaults = true }
            val tseStatusBody =
                "id: 9\ndata: " +
                    serverJson.encodeToString<NotificationDto.NotificationMessage>(
                        NotificationDto.TseStatusUpdateDto(timestamp = "t", connected = true, healthy = true, mode = "DISABLED"),
                    ) +
                    "\n\n"
            val noStatusBody =
                "id: 1\ndata: " +
                    serverJson.encodeToString<NotificationDto.NotificationMessage>(
                        NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = "hi"),
                    ) +
                    "\n\n"
            val recorded = CopyOnWriteArrayList<String?>()
            val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost"))
            val engine =
                SseStubEngine(
                    eventBody = tseStatusBody,
                    recordedLastEventIds = recorded,
                    eventBodyForHost = { host -> if (host == "otherhost") noStatusBody else null },
                )
            val connection = newConnection(engine, localDb = localDb)

            connection.connect("api/notifications/stream")
            awaitReconnect(recorded)
            connection.disconnect()
            assertEquals("DISABLED", connection.latestTseStatus.value?.mode)

            recorded.clear()
            localDb.setApiBaseUrl("http://otherhost")
            connection.connect("api/notifications/stream")
            awaitReconnect(recorded)
            connection.disconnect()

            assertNull(connection.latestTseStatus.value)
        }

    /**
     * The `Last-Event-ID` of the next request this engine records for [host], skipping requests to other hosts. Real
     * time and bounded, as in [awaitReconnect]. A disconnected stream's job can still issue one last request to its own
     * server, so requests are matched by host rather than by position.
     */
    private suspend fun Channel<HttpRequestData>.nextLastEventIdSentTo(host: String): String? =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                var request = receive()
                while (request.url.host != host) request = receive()
                request.headers[HttpHeaders.LastEventID]
            }
        }

    @Test
    fun connectingToADifferentServer_sendsItNoLastEventIdFromThePreviousServer() =
        runTest {
            // An event id names a position in one server's stream. After the base URL changes mid-process (pairing
            // screen / LaunchCoordinator server-address change), server A's id sent to server B would ask B to resume
            // after an unrelated event of its own — skipping or replaying events.
            val requests = Channel<HttpRequestData>(Channel.UNLIMITED)
            val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://server-a"))
            val engine =
                SseStubEngine(
                    eventBody = singleEventBody(id = "7"),
                    recordedLastEventIds = CopyOnWriteArrayList(),
                    eventBodyForHost = { host -> if (host == "server-b") singleEventBody(id = "3") else null },
                    onRequest = { requests.trySend(it) },
                )
            val connection = newConnection(engine, localDb = localDb)

            connection.connect("api/notifications/stream")
            assertNull(requests.nextLastEventIdSentTo("server-a"), "first connect to server A must not send a Last-Event-ID")
            // The finite body ends after the id=7 event, so this is the reconnect.
            assertEquals("7", requests.nextLastEventIdSentTo("server-a"), "a reconnect to server A must resume after its event")
            connection.disconnect()

            localDb.setApiBaseUrl("http://server-b")
            connection.connect("api/notifications/stream")
            assertNull(requests.nextLastEventIdSentTo("server-b"), "server A's event id must not be sent to server B")
            assertEquals("3", requests.nextLastEventIdSentTo("server-b"), "a reconnect to server B must resume after its event")
            connection.disconnect()
        }

    @Test
    fun statusesSentBackToBack_reachACollectorInTheOrderTheServerSentThem() =
        runTest {
            // The view model warns on a transition into DISABLED by comparing with the previous status, so a
            // NORMAL-then-DISABLED pair delivered reversed would raise a warning for a change that did not
            // happen, or miss one that did. Each message used to be emitted from its own coroutine on
            // Dispatchers.Default, which keeps no order. A short run rarely shows it — the collector usually
            // suspends between events and the launched emit runs first — so this sends a burst: 40 events
            // passed against that dispatch, 500 failed it.
            val serverJson = Json { encodeDefaults = true }
            val sent =
                List(500) { i ->
                    NotificationDto.TseStatusUpdateDto(
                        timestamp = "t$i",
                        connected = true,
                        healthy = true,
                        mode = if (i % 2 == 0) "NORMAL" else "DISABLED",
                    )
                }
            val body =
                sent.withIndex().joinToString("") { (i, status) ->
                    "id: $i\ndata: ${serverJson.encodeToString<NotificationDto.NotificationMessage>(status)}\n\n"
                }
            val recorded = CopyOnWriteArrayList<String?>()
            val connection = newConnection(SseStubEngine(body, recorded))
            val received = CopyOnWriteArrayList<String>()
            val subscribed = CompletableDeferred<Unit>()
            backgroundScope.launch(Dispatchers.Default) {
                connection.messages
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { if (it is NotificationDto.TseStatusUpdateDto) received.add(it.timestamp) }
            }
            subscribed.await()

            connection.connect("api/notifications/stream")
            // Real time, as in awaitReconnect: the stream runs on Dispatchers.Default.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    while (received.size < sent.size) {
                        delay(10)
                    }
                }
            }
            connection.disconnect()

            // The finite body ends and the stream reconnects, replaying it; only the first pass is compared.
            assertEquals(sent.map { it.timestamp }, received.take(sent.size))
        }

    /** Token validation that waits until the test releases it, so a disconnect() can land while connect() is inside it. */
    private class GatedAuthApiClient : AuthApiClient {
        val entered = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Result<ValidationResponse>>()

        override suspend fun login(request: LoginRequest): LoginResponse = error("not used")

        override suspend fun logout(): Unit = error("not used")

        override suspend fun getCurrentUser(): EmployeeInfoDto.EmployeeInfo = error("not used")

        override suspend fun validateToken(): ValidationResponse {
            entered.complete(Unit)
            return outcome.await().getOrThrow()
        }
    }

    /**
     * Starts connect() with a token that needs validating, disconnects while that validation is still waiting, and
     * only then lets it finish with [outcome]. Returns once connect() has returned.
     */
    private suspend fun TestScope.disconnectWhileTheTokenIsValidated(
        connection: SseNotificationConnection,
        auth: GatedAuthApiClient,
        outcome: Result<ValidationResponse>,
    ) {
        val connecting = launch { connection.connect("api/notifications/stream") }
        auth.entered.await()
        connection.disconnect()
        auth.outcome.complete(outcome)
        connecting.join()
    }

    /** Real time and bounded: a stream job, had one been started, would issue its request on Dispatchers.Default. */
    private suspend fun awaitRequest(requested: CompletableDeferred<Unit>): Unit? =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeoutOrNull(500) { requested.await() }
        }

    @Test
    fun disconnectWhileTheTokenIsValidated_opensNoStream() =
        runTest {
            // connect() checks the token before it opens the stream. A disconnect() that lands during that check
            // (a logout while a slow validation is in flight) must win: a stream opened after it is one disconnect()
            // no longer holds, so nothing could close it.
            val requested = CompletableDeferred<Unit>()
            val auth = GatedAuthApiClient()
            val connection =
                newConnection(
                    SseStubEngine(singleEventBody(), CopyOnWriteArrayList(), onRequest = { requested.complete(Unit) }),
                    authApiClient = auth,
                    tokenExpiresInSeconds = 0,
                )

            disconnectWhileTheTokenIsValidated(connection, auth, Result.success(ValidationResponse(valid = true)))

            assertNull(awaitRequest(requested), "no stream request may follow disconnect()")
            assertEquals(NotificationConnection.ConnectionState.Disconnected, connection.connectionState.value)
            assertEquals(emptyList(), connection.errors.replayCache, "an abandoned connect reports nothing")
        }

    @Test
    fun disconnectWhileTheTokenIsValidated_thenValidationFails_reportsNoAuthenticationFailure() =
        runTest {
            // The same race, but the validation then fails. `errors` replays its last value and disconnect() has
            // already cleared it, so a failure recorded now would greet the next session's view model and send the
            // user straight back to the login screen for an attempt nobody is waiting on.
            val auth = GatedAuthApiClient()
            val connection =
                newConnection(
                    SseStubEngine(singleEventBody(), CopyOnWriteArrayList()),
                    authApiClient = auth,
                    tokenExpiresInSeconds = 0,
                )

            disconnectWhileTheTokenIsValidated(connection, auth, Result.failure(IllegalStateException("refresh failed")))

            assertEquals(NotificationConnection.ConnectionState.Disconnected, connection.connectionState.value)
            assertEquals(emptyList(), connection.errors.replayCache, "an abandoned connect reports no authentication failure")
        }
}
