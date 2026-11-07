package org.example.project.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for the "kicked back to login right after re-login" bug, ported
 * from the deleted WebSocketManager test to the SSE transport.
 *
 * The [SseNotificationConnection] is a process-wide singleton whose `errors`
 * SharedFlow uses `replay = 1`. When the access token can't be refreshed, `connect()`
 * emits an [NotificationConnection.NotificationConnectionError.AuthenticationFailed]
 * which is retained in the replay cache. After the user re-authenticates, the freshly
 * recreated NotificationViewModel subscribes to `errors` and would immediately receive
 * the *stale* AuthenticationFailed, re-triggering logout.
 *
 * [SseNotificationConnection.clearErrors] (also invoked by [disconnect]) must drop the
 * retained error so the next session's subscriber starts clean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseNotificationConnectionAuthErrorReplayTest {
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

    private fun connection(authApiClient: FakeAuthApiClient): SseNotificationConnection {
        val localDb = FakeLocalDatabaseManager.create()
        val tokenStore = FakeTokenStore.create()
        val client = HttpClient(MockEngine { respondOk() })
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
            reconnectPolicy = ReconnectPolicy(),
        )
    }

    /**
     * No token stored + the refresh is *refused* -> connect() emits AuthenticationFailed.
     *
     * The 401 is what makes this an authentication failure rather than an outage. This fake used
     * to throw a bare IllegalStateException, which `ensureFreshToken` has classified as
     * UNREACHABLE since 2b24a74e7 ("a server outage no longer signs the operator out") — it lands
     * in the generic `catch (e: Exception)` arm and schedules a reconnect instead. The test kept
     * passing on the ConnectionFailed the reconnect ladder then emitted, because its precondition
     * only asked that *some* error be replayed, so it stopped covering the auth path it is named
     * for without ever going red.
     */
    private fun failingAuth() =
        FakeAuthApiClient().apply {
            validateResult =
                Result.failure(
                    PosApiException(PosErrorCode.AUTH_SESSION_EXPIRED, 401, "refresh refused", null),
                )
        }

    /**
     * The AuthenticationFailed is emitted from the connection's own coroutine on
     * [Dispatchers.Default], which runTest's virtual clock does not advance. A virtual-time
     * `withTimeoutOrNull { errors.first() }` therefore skips straight to the timeout whenever the
     * emit loses the race to the test scheduler going idle — it passes on a quiet laptop and fails
     * on a loaded CI runner. Wait for it in real time instead, the same way
     * SseNotificationConnectionTokenRenewalTest's transport-failure test does.
     */
    private suspend fun SseNotificationConnection.awaitAuthenticationFailed(): Boolean =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeoutOrNull(5_000) {
                while (errors.replayCache.none {
                        it is NotificationConnection.NotificationConnectionError.AuthenticationFailed
                    }
                ) {
                    delay(5)
                }
                true
            } ?: false
        }

    @Test
    fun clearErrors_drops_replayed_authentication_error() =
        runTest {
            val sse = connection(failingAuth())

            sse.connect("api/notifications/stream")

            // Precondition: the error is cached in the replay buffer (a fresh collector sees it).
            assertTrue(
                sse.awaitAuthenticationFailed(),
                "precondition: auth error should be cached for replay",
            )

            sse.clearErrors()

            // A NEW subscriber (the NotificationViewModel recreated after re-login) must
            // NOT receive the stale authentication error.
            val afterClear = withTimeoutOrNull(300) { sse.errors.first() }
            assertNull(
                afterClear,
                "stale auth error must not be replayed to a new subscriber after clearErrors()",
            )
        }

    @Test
    fun disconnect_also_clears_replayed_authentication_error() =
        runTest {
            val sse = connection(failingAuth())

            sse.connect("api/notifications/stream")
            assertTrue(sse.awaitAuthenticationFailed(), "precondition: auth error should be cached for replay")

            sse.disconnect()

            val afterDisconnect = withTimeoutOrNull(300) { sse.errors.first() }
            assertNull(
                afterDisconnect,
                "disconnect (logout teardown) must also drop the retained auth error",
            )
        }
}
