package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for what an order station does while its network stays down.
 *
 * [ReconnectPolicy.nextDelay] never gives up — once the fast ladder is spent it hands out the slow
 * interval forever, so a station that lost Wi-Fi comes back on its own instead of waiting for someone
 * to restart the app. That alone left [SseNotificationConnection] worse off: its `if (delayMs == null)`
 * branch was the **sole** producer of [NotificationConnection.ConnectionState.Failed] and of
 * `ConnectionFailed(RETRIES_EXHAUSTED)`, so retries continued but nobody was ever told the connection
 * had degraded. These two tests pin both halves — the retrying, and the one-time notice.
 *
 * ## Fixture notes
 *
 * - **The engine always fails.** A connect that *succeeds* calls `reconnectPolicy.reset()`, so the
 *   ladder could never be spent; only a server that cannot be reached at all drives the policy to
 *   exhaustion. It must still advertise [SSECapability] — `MockEngine` does not, and the SSE plugin
 *   then throws before a request reaches the engine, so nothing would count as an attempt.
 * - **Real time, not `runTest`'s virtual clock.** [SseNotificationConnection] drives its reconnect loop
 *   on an internal `CoroutineScope(Dispatchers.Default)`, which the virtual clock does not control, so
 *   `advanceTimeBy` would advance nothing at all. The waits below poll wall-clock time under a bound.
 * - **Tiny delays.** The real defaults (5 attempts climbing to 30 s, then 60 s) would make that
 *   wall-clock wait minutes long. `maxAttempts = 2` with 5/10 ms delays reaches exhaustion and several
 *   slow retries in well under a second, which is why the thresholds below are small numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalAPI::class)
class SseReconnectAfterExhaustionTest {
    private companion object {
        const val ENDPOINT = "api/notifications/stream"

        /**
         * Requests to wait for. With `maxAttempts = 2` the ladder is spent on the 2nd, so this leaves
         * four more — three of them at the slow interval, which is what a per-retry notice would
         * duplicate itself over.
         */
        const val ATTEMPTS_WELL_PAST_EXHAUSTION = 6

        /** Bounds every wall-clock poll; the whole ladder plus four slow retries is ~40 ms. */
        const val AWAIT_TIMEOUT_MS = 5_000L

        /**
         * A slow interval far beyond anything these tests wait for. Once the ladder is spent the connection parks for
         * this long, so any further attempt can only have come from `requestImmediateReconnect` — never from the timer.
         */
        const val SLOW_RETRY_BEYOND_TEST_PATIENCE_MS = 30_000L
    }

    /**
     * An SSE-capable engine standing in for an unreachable server: it counts the request, then fails it
     * the way a dropped network does. Counting happens before the throw, so an attempt the connection
     * made is recorded even though it never produced a stream.
     */
    private class UnreachableServerEngine(
        private val attempts: AtomicInteger,
    ) : HttpClientEngineBase("unreachable-stub") {
        override val config = object : io.ktor.client.engine.HttpClientEngineConfig() {}

        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
            setOf(SSECapability, HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            attempts.incrementAndGet()
            throw IOException("simulated network loss")
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

    /**
     * A connection whose every connect fails, over a policy short enough to exhaust in milliseconds.
     * The reconciler is never reached — it runs only after a stream opens — but the constructor needs it.
     */
    private suspend fun newConnection(
        attempts: AtomicInteger,
        slowRetryDelayMs: Long = 10,
    ): SseNotificationConnection {
        val client =
            HttpClient(UnreachableServerEngine(attempts)) {
                install(SSE)
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout)
            }
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost"))
        val tokenStore = FakeTokenStore.create()
        // Well outside the renewal buffer, so connect() never stops to validate and each attempt is a
        // real request to the engine.
        tokenStore.storeTokens("access-token", "refresh-token", expiresIn = 3600)

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
            authApiClient = FakeAuthApiClient(),
            localDb = localDb,
            reconciler = reconciler,
            // 5 ms rungs and a 10 ms slow interval: the production 1 s→30 s→60 s schedule would make the
            // wall-clock waits below take minutes.
            reconnectPolicy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 5, maxDelayMs = 5, slowRetryDelayMs = slowRetryDelayMs),
            appVersion = "0.0.0-test",
        )
    }

    /**
     * True once [reached] holds. Polls real time — the reconnect loop runs on Dispatchers.Default, which
     * runTest's virtual clock does not advance — and returns false on timeout rather than throwing, so
     * the caller can fail with a message naming what it was still waiting for.
     */
    private suspend fun awaitRealTime(
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        reached: () -> Boolean,
    ): Boolean =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeoutOrNull(timeoutMs) {
                while (!reached()) {
                    delay(5)
                }
                true
            } ?: false
        }

    /**
     * Real-time pause letting an attempt already in flight finish. The re-entrancy guard in `connect` bails while a
     * stream job is still unwinding, so a reconnect requested the instant the attempt count moves could be dropped for
     * a reason unrelated to what the test is asserting.
     */
    private suspend fun settleRealTime(millis: Long = 150) {
        withContext(Dispatchers.Default) { delay(millis) }
    }

    private fun NotificationConnection.NotificationConnectionError.isRetriesExhausted(): Boolean =
        this is NotificationConnection.NotificationConnectionError.ConnectionFailed &&
            reason == NotificationConnection.ConnectionFailureReason.RETRIES_EXHAUSTED

    @Test
    fun retriesContinueAfterTheFastLadderIsSpent() =
        runTest {
            // A station whose Wi-Fi drops for six minutes has to come back by itself. Stopping at the last
            // fast attempt left it dark until someone restarted the app.
            val attempts = AtomicInteger()
            val connection = newConnection(attempts)

            connection.connect(ENDPOINT)
            val keptRetrying = awaitRealTime { attempts.get() >= ATTEMPTS_WELL_PAST_EXHAUSTION }
            connection.disconnect()

            assertTrue(
                keptRetrying,
                "reconnects must continue past the spent fast ladder (maxAttempts = 2); they stopped after " +
                    "${attempts.get()} attempts",
            )
        }

    @Test
    fun theRetriesExhaustedNoticeIsEmittedOnceNotOncePerSlowRetry() =
        runTest {
            // The notice is what turns the footer red and offers a manual retry. Firing it on every slow
            // retry would re-toast the same outage every minute; never firing it — the state this test was
            // written against — leaves the user looking at a stale screen with no hint anything is wrong.
            val attempts = AtomicInteger()
            val received = CopyOnWriteArrayList<NotificationConnection.NotificationConnectionError>()
            val subscribed = CompletableDeferred<Unit>()
            val connection = newConnection(attempts)
            // `errors` replays only its last value, so a count has to come from a live collector. It runs on
            // Dispatchers.Default because that is where the connection emits.
            backgroundScope.launch(Dispatchers.Default) {
                connection.errors
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { received.add(it) }
            }
            subscribed.await()

            connection.connect(ENDPOINT)
            val keptRetrying = awaitRealTime { attempts.get() >= ATTEMPTS_WELL_PAST_EXHAUSTION }
            // Awaited rather than read straight away, so a slow delivery cannot be misread as "never fired".
            val noticed = awaitRealTime { received.any { it.isRetriesExhausted() } }
            connection.disconnect()

            val seen = received.map { it.diagnostic }
            assertTrue(keptRetrying, "reconnects stopped after ${attempts.get()} attempts; errors seen: $seen")
            assertTrue(
                noticed,
                "the fast ladder was spent over ${attempts.get()} attempts but no RETRIES_EXHAUSTED notice " +
                    "reached the UI; errors seen: $seen",
            )
            assertEquals(
                1,
                received.count { it.isRetriesExhausted() },
                "one RETRIES_EXHAUSTED notice for the whole outage, not one per slow retry; errors seen: $seen",
            )
        }

    @Test
    fun aReturningNetworkReconnectsWithoutWaitingOutTheSlowInterval() =
        runTest {
            // The station's Wi-Fi is already back while the connection is parked on a slow interval — 30 s here, a
            // minute in production. Waiting it out is the gap this closes; nothing else can produce another attempt.
            val attempts = AtomicInteger()
            val connection = newConnection(attempts, slowRetryDelayMs = SLOW_RETRY_BEYOND_TEST_PATIENCE_MS)

            connection.connect(ENDPOINT)
            // Failed is set exactly once, on the transition into exhaustion — the moment the connection parks.
            val parked = awaitRealTime { connection.connectionState.value == NotificationConnection.ConnectionState.Failed }
            settleRealTime()
            val whileParked = attempts.get()

            connection.requestImmediateReconnect()
            val reconnected = awaitRealTime { attempts.get() > whileParked }
            connection.disconnect()

            assertTrue(parked, "the fast ladder (maxAttempts = 2) never parked; attempts = ${attempts.get()}")
            assertTrue(
                reconnected,
                "a station whose network returned must reconnect at once: attempts stayed at $whileParked, so it was " +
                    "still waiting out the ${SLOW_RETRY_BEYOND_TEST_PATIENCE_MS}ms slow interval",
            )
        }

    @Test
    fun afterDisconnectAReturningNetworkDoesNotResurrectTheStream() =
        runTest {
            // disconnect() is deliberate — a logout, or the foreground service stopping. Reconnecting on the next
            // network callback would reopen a stream the app closed on purpose, under the session it closed it for.
            val attempts = AtomicInteger()
            val connection = newConnection(attempts, slowRetryDelayMs = SLOW_RETRY_BEYOND_TEST_PATIENCE_MS)

            connection.connect(ENDPOINT)
            val reachedTheEngine = awaitRealTime { attempts.get() >= 1 }
            connection.disconnect()
            settleRealTime()
            val afterDisconnect = attempts.get()

            connection.requestImmediateReconnect()
            // Absence needs a wait of its own: give a reconnect room to appear, then show that none did.
            val resurrected = awaitRealTime(timeoutMs = 300) { attempts.get() > afterDisconnect }

            assertTrue(reachedTheEngine, "the fixture never reached the engine, so nothing was there to disconnect")
            assertFalse(
                resurrected,
                "requestImmediateReconnect must stay suppressed after disconnect(); attempts went from " +
                    "$afterDisconnect to ${attempts.get()}",
            )
        }
}
