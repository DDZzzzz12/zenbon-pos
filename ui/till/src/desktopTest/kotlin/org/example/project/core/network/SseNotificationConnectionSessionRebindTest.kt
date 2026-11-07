package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
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
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.example.project.core.auth.TokenStore
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Emulator pass 4, NOTE-4: Android restarted the crashed notification service with no Activity. It
 * opened the stream with the token still stored — the manager's. The cashier then logged in; the
 * login's start() reached connect(), whose re-entrancy guard saw a live stream and dropped the call,
 * so notifications for the cashier went to the manager's stream until the next session reset.
 *
 * The stream must follow the current session: a connect() for another employee re-binds it, while a
 * repeated connect() for the same employee (including after a token renewal) still keeps the one
 * stream the server allows per employee.
 */
@OptIn(InternalAPI::class, ExperimentalEncodingApi::class)
class SseNotificationConnectionSessionRebindTest {
    /** Serves a stream that never ends, so the first stream is still live when the second connect() arrives. */
    private class OpenStreamEngine(
        private val authorizations: CopyOnWriteArrayList<String?>,
    ) : HttpClientEngineBase("sse-open-stub") {
        override val config = object : io.ktor.client.engine.HttpClientEngineConfig() {}

        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(SSECapability, HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            authorizations.add(data.headers[HttpHeaders.Authorization])
            val status = HttpStatusCode.OK
            val headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            val body = ByteChannel()
            val callContext = callContext()
            val adapted: Any =
                data.attributes
                    .getOrNull(ResponseAdapterAttributeKey)
                    ?.adapt(data, status, headers, body, data.body, callContext)
                    ?: body
            return HttpResponseData(status, GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, adapted, callContext)
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

    /** An unsigned JWT the client-side decoder accepts; [nonce] makes two tokens for one employee differ. */
    private fun jwtFor(
        employeeId: String,
        employeeNumber: String,
        nonce: String = "1",
    ): String {
        fun part(json: String) = Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        val header = part("""{"alg":"none"}""")
        val payload = part("""{"sub":"$employeeId","employeeNumber":"$employeeNumber","jti":"$nonce"}""")
        return "$header.$payload.sig"
    }

    private fun connectionFor(
        tokenStore: TokenStore,
        authorizations: CopyOnWriteArrayList<String?>,
    ): SseNotificationConnection {
        val localDb = FakeLocalDatabaseManager.create(mapOf("api_base_url" to "http://localhost"))
        return SseNotificationConnection(
            client =
                HttpClient(OpenStreamEngine(authorizations)) {
                    install(SSE)
                    install(HttpTimeout)
                },
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

    private suspend fun SseNotificationConnection.awaitConnected() =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { connectionState.first { it == NotificationConnection.ConnectionState.Connected } }
        }

    /** Waits (real time) for [count] stream requests, then a little longer so a spurious extra one would show. */
    private suspend fun awaitRequests(
        authorizations: List<String?>,
        count: Int,
    ) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (authorizations.size < count) delay(10) }
        delay(200)
    }

    @Test
    fun `a login by another employee re-binds a stream still open for the previous one`() =
        runTest {
            val authorizations = CopyOnWriteArrayList<String?>()
            val tokenStore = FakeTokenStore.create()
            val manager = jwtFor("7", "M001")
            tokenStore.storeTokens(manager, "refresh-m", expiresIn = 3600)
            val connection = connectionFor(tokenStore, authorizations)

            // The crash-restarted service opens the stream for the stored session.
            connection.connect("api/notifications/stream")
            connection.awaitConnected()

            // The cashier logs in; the login starts the service again, which calls connect().
            val cashier = jwtFor("3", "K001")
            tokenStore.storeTokens(cashier, "refresh-k", expiresIn = 3600)
            connection.connect("api/notifications/stream")
            awaitRequests(authorizations, 2)
            connection.disconnect()

            assertEquals(listOf("Bearer $manager", "Bearer $cashier"), authorizations.toList())
        }

    @Test
    fun `a repeated connect for the same employee keeps the one open stream`() =
        runTest {
            val authorizations = CopyOnWriteArrayList<String?>()
            val tokenStore = FakeTokenStore.create()
            tokenStore.storeTokens(jwtFor("3", "K001", nonce = "a"), "refresh-1", expiresIn = 3600)
            val connection = connectionFor(tokenStore, authorizations)

            connection.connect("api/notifications/stream")
            connection.awaitConnected()

            // A renewed token for the same person is the same session owner: no second stream,
            // which the server would answer by force-closing the first (the ping-pong the guard prevents).
            tokenStore.storeTokens(jwtFor("3", "K001", nonce = "b"), "refresh-2", expiresIn = 3600)
            connection.connect("api/notifications/stream")
            awaitRequests(authorizations, 1)
            connection.disconnect()

            assertEquals(1, authorizations.size, "expected the one stream to be kept, got $authorizations")
        }
}
