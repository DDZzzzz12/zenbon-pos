package org.example.project.core.network

import kotlinx.coroutines.test.runTest
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the seq-delta reconcile path of [NotificationReconciler]:
 * within-window delta, past-window full-sync fallback, and multi-page drain.
 */
class NotificationReconcilerDeltaTest {
    private class FakeApprovalClient : ApprovalRequestApiClient {
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

    private class FakeBillingClient : BillingRequestApiClient {
        override suspend fun getPendingBillingRequests(): List<NotificationDto.BillingRequestNotificationDto> = emptyList()
    }

    private class RecordingSyncService : FakeSyncService() {
        var completeSyncCount = 0

        override suspend fun performCompleteSync() {
            completeSyncCount++
        }
    }

    /** Returns a response keyed by the requested `since`. */
    private class PagedChangeFeedApiClient(
        private val pages: Map<Long, ChangeFeedDto.ResponseDto>,
    ) : ChangeFeedApiClient {
        val requestedSince = mutableListOf<Long>()

        override suspend fun getChanges(
            since: Long,
            limit: Int,
        ): ChangeFeedDto.ResponseDto {
            requestedSince += since
            return pages[since] ?: error("no page configured for since=$since")
        }
    }

    private fun entry(seq: Long) =
        ChangeFeedDto.EntryDto(
            seq = seq,
            entityType = ChangeEntityType.PRODUCT,
            entityId = seq.toString(),
            operation = LogOperation.UPDATE,
        )

    @Test
    fun `within window applies delta and advances cursor without full sync`() =
        runTest {
            val localDb = FakeLocalDatabaseManager.create()
            localDb.setLastSeenSeq(10L)
            val sync = RecordingSyncService()
            val feed =
                PagedChangeFeedApiClient(
                    mapOf(
                        10L to
                            ChangeFeedDto.ResponseDto(
                                entries = listOf(entry(11L)),
                                highWaterSeq = 11L,
                                minAvailableSeq = 5L,
                            ),
                    ),
                )
            val reconciler =
                NotificationReconciler(
                    syncService = sync,
                    changeFeedApiClient = feed,
                    localDatabaseManager = localDb,
                    approvalApiClient = FakeApprovalClient(),
                    billingApiClient = FakeBillingClient(),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = FakePermissionManager.create(),
                )

            reconciler.reconcile { }

            assertEquals(1, sync.appliedDeltas.size, "delta should be applied exactly once")
            assertEquals(listOf(entry(11L)), sync.appliedDeltas.single())
            assertEquals(11L, localDb.getLastSeenSeq(), "cursor advanced to applied entry seq")
            assertEquals(0, sync.completeSyncCount, "full sync must not run on the delta path")
        }

    @Test
    fun `past window falls back to full sync and jumps cursor to high water`() =
        runTest {
            val localDb = FakeLocalDatabaseManager.create()
            localDb.setLastSeenSeq(3L)
            val sync = RecordingSyncService()
            val feed =
                PagedChangeFeedApiClient(
                    mapOf(
                        3L to
                            ChangeFeedDto.ResponseDto(
                                entries = listOf(entry(50L)),
                                highWaterSeq = 90L,
                                minAvailableSeq = 40L,
                            ),
                    ),
                )
            val reconciler =
                NotificationReconciler(
                    syncService = sync,
                    changeFeedApiClient = feed,
                    localDatabaseManager = localDb,
                    approvalApiClient = FakeApprovalClient(),
                    billingApiClient = FakeBillingClient(),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = FakePermissionManager.create(),
                )

            reconciler.reconcile { }

            assertEquals(1, sync.completeSyncCount, "full sync must run when cursor is past retention")
            assertEquals(90L, localDb.getLastSeenSeq(), "cursor jumps to high-water after full sync")
            assertTrue(sync.appliedDeltas.isEmpty(), "no delta applied on full-sync fallback")
        }

    @Test
    fun `drain loop pages through capped feed until caught up`() =
        runTest {
            val localDb = FakeLocalDatabaseManager.create()
            localDb.setLastSeenSeq(0L)
            val sync = RecordingSyncService()
            // First page (since=0): entries 1..2, last seq 2 < highWater 4 -> drain.
            // Second page (since=2): entries 3..4, last seq 4 == highWater 4 -> stop.
            val feed =
                PagedChangeFeedApiClient(
                    mapOf(
                        0L to
                            ChangeFeedDto.ResponseDto(
                                entries = listOf(entry(1L), entry(2L)),
                                highWaterSeq = 4L,
                                minAvailableSeq = 0L,
                            ),
                        2L to
                            ChangeFeedDto.ResponseDto(
                                entries = listOf(entry(3L), entry(4L)),
                                highWaterSeq = 4L,
                                minAvailableSeq = 0L,
                            ),
                    ),
                )
            val reconciler =
                NotificationReconciler(
                    syncService = sync,
                    changeFeedApiClient = feed,
                    localDatabaseManager = localDb,
                    approvalApiClient = FakeApprovalClient(),
                    billingApiClient = FakeBillingClient(),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = FakePermissionManager.create(),
                )

            reconciler.reconcile { }

            assertEquals(2, sync.appliedDeltas.size, "both pages applied")
            assertEquals(listOf(entry(1L), entry(2L)), sync.appliedDeltas[0])
            assertEquals(listOf(entry(3L), entry(4L)), sync.appliedDeltas[1])
            assertEquals(4L, localDb.getLastSeenSeq(), "cursor ends at high-water")
            assertEquals(listOf(0L, 2L), feed.requestedSince, "drain pages from the last applied seq")
            assertEquals(0, sync.completeSyncCount)
        }
}
