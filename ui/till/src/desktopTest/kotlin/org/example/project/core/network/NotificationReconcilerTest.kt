package org.example.project.core.network

import kotlinx.coroutines.test.runTest
import org.example.project.core.auth.PermissionManager
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.RotationApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeSyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationReconcilerTest {
    /** Drives the past-window full-sync fallback: cursor 0 < minAvailableSeq. */
    private class FullSyncChangeFeedApiClient : ChangeFeedApiClient {
        override suspend fun getChanges(
            since: Long,
            limit: Int,
        ): ChangeFeedDto.ResponseDto = ChangeFeedDto.ResponseDto(entries = emptyList(), highWaterSeq = 100L, minAvailableSeq = 50L)
    }

    private class FakeApprovalClient(
        val pending: List<AuthDto.ApprovalRequestDto>,
    ) : ApprovalRequestApiClient {
        var pendingFetches = 0
            private set

        override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> {
            pendingFetches++
            return pending
        }

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

    private class FakeBillingClient(
        val pending: List<NotificationDto.BillingRequestNotificationDto>,
    ) : BillingRequestApiClient {
        override suspend fun getPendingBillingRequests(): List<NotificationDto.BillingRequestNotificationDto> = pending
    }

    private val approvalRequest =
        AuthDto.ApprovalRequestDto(
            requestId = "req-1",
            requestingEmployeeId = "emp-1",
            deviceId = "dev-1",
            operation = "TEST_OP",
            requiredCapabilities = emptySet(),
            details = emptyMap(),
            timestamp = "2026-01-01T00:00:00Z",
        )

    private val billingRequest =
        NotificationDto.BillingRequestNotificationDto(
            timestamp = "2026-01-01T00:00:00Z",
            tableId = 5,
            tableNumber = "T5",
            requestingEmployeeNumber = "100",
        )

    /** A principal holding exactly [capabilities], as the server reported them. */
    private fun principalHolding(vararg capabilities: Capability): PermissionManager =
        FakePermissionManager.create().apply { setReportedCapabilities(capabilities.map { it.name }.toSet()) }

    @Test
    fun `reconcile runs full sync then re-injects pending approvals`() =
        runTest {
            var synced = false
            val sync =
                object : FakeSyncService() {
                    override suspend fun performCompleteSync() {
                        synced = true
                    }
                }
            val reconciler =
                NotificationReconciler(
                    syncService = sync,
                    changeFeedApiClient = FullSyncChangeFeedApiClient(),
                    localDatabaseManager = FakeLocalDatabaseManager.create(),
                    approvalApiClient = FakeApprovalClient(listOf(approvalRequest)),
                    billingApiClient = FakeBillingClient(listOf(billingRequest)),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = principalHolding(Capability.SESSION_READ),
                )
            val emitted = mutableListOf<NotificationDto.NotificationMessage>()
            reconciler.reconcile { emitted += it }
            assertTrue(synced)
            assertEquals(1, emitted.count { it is NotificationDto.AuthorizationNotificationDto })
            assertEquals(1, emitted.count { it is NotificationDto.BillingRequestNotificationDto })
        }

    @Test
    fun `a principal without SESSION_READ never asks for pending approvals`() =
        runTest {
            // An order station's masked set: it takes orders, but GET /api/auth/approvals/pending is
            // Cap(SESSION_READ) and answered 403 on every stream connect.
            val approvals = FakeApprovalClient(listOf(approvalRequest))
            val reconciler =
                NotificationReconciler(
                    syncService = FakeSyncService(),
                    changeFeedApiClient = FullSyncChangeFeedApiClient(),
                    localDatabaseManager = FakeLocalDatabaseManager.create(),
                    approvalApiClient = approvals,
                    billingApiClient = FakeBillingClient(listOf(billingRequest)),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = principalHolding(Capability.CATALOG_READ, Capability.ORDER_WRITE, Capability.TABLE_WRITE),
                )
            val emitted = mutableListOf<NotificationDto.NotificationMessage>()
            reconciler.reconcile { emitted += it }
            assertEquals(0, approvals.pendingFetches, "the approvals endpoint must not be called")
            assertEquals(0, emitted.count { it is NotificationDto.AuthorizationNotificationDto })
            assertEquals(1, emitted.count { it is NotificationDto.BillingRequestNotificationDto }, "billing replay is unaffected")
        }

    @Test
    fun `a signed-out principal never asks for pending approvals`() =
        runTest {
            val approvals = FakeApprovalClient(listOf(approvalRequest))
            val reconciler =
                NotificationReconciler(
                    syncService = FakeSyncService(),
                    changeFeedApiClient = FullSyncChangeFeedApiClient(),
                    localDatabaseManager = FakeLocalDatabaseManager.create(),
                    approvalApiClient = approvals,
                    billingApiClient = FakeBillingClient(emptyList()),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = FakePermissionManager.create(),
                )
            reconciler.reconcile { }
            assertEquals(0, approvals.pendingFetches)
        }

    @Test
    fun `no reported capabilities but a temporary manager role still asks for pending approvals`() =
        runTest {
            val approvals = FakeApprovalClient(listOf(approvalRequest))
            val permissionManager = FakePermissionManager.create().apply { setTemporaryRole(EmployeeRole.MANAGER) }
            val reconciler =
                NotificationReconciler(
                    syncService = FakeSyncService(),
                    changeFeedApiClient = FullSyncChangeFeedApiClient(),
                    localDatabaseManager = FakeLocalDatabaseManager.create(),
                    approvalApiClient = approvals,
                    billingApiClient = FakeBillingClient(emptyList()),
                    rotationApiClient =
                        org.example.project.testutils.fakes
                            .NoopRotationApiClient(),
                    permissionManager = permissionManager,
                )
            reconciler.reconcile { }
            assertEquals(1, approvals.pendingFetches, "the approvals endpoint must be called once")
        }

    private class RecordingRotationClient(
        private val snapshot: RotationDto.NextSnapshotDto? = null,
    ) : RotationApiClient {
        var fetches = 0
            private set

        override suspend fun getNext(): RotationDto.NextSnapshotDto {
            fetches++
            return snapshot ?: error("rotation endpoint down")
        }
    }

    private fun rotationReconciler(
        rotation: RotationApiClient,
        permissions: PermissionManager,
    ) = NotificationReconciler(
        syncService = FakeSyncService(),
        changeFeedApiClient = FullSyncChangeFeedApiClient(),
        localDatabaseManager = FakeLocalDatabaseManager.create(),
        approvalApiClient = FakeApprovalClient(emptyList()),
        billingApiClient = FakeBillingClient(listOf(billingRequest)),
        rotationApiClient = rotation,
        permissionManager = permissions,
    )

    @Test
    fun `with TABLE_READ the rotation snapshot is emitted as an isSnapshot update`() =
        runTest {
            val ref = RotationDto.EmployeeRefDto(employeeId = "e1", employeeNumber = "101", displayName = "Anna")
            val rotation = RecordingRotationClient(RotationDto.NextSnapshotDto(version = 0, next = ref))
            val emitted = mutableListOf<NotificationDto.NotificationMessage>()
            rotationReconciler(rotation, principalHolding(Capability.TABLE_READ)).reconcile { emitted += it }
            val updates = emitted.filterIsInstance<NotificationDto.RotationNextUpdateDto>()
            assertEquals(1, updates.size)
            assertTrue(updates.single().isSnapshot, "a reconnect fetch must replace the store unconditionally")
            assertEquals(0L, updates.single().version)
            assertEquals(ref, updates.single().next)
        }

    @Test
    fun `without TABLE_READ the rotation endpoint is not called`() =
        runTest {
            val rotation = RecordingRotationClient(RotationDto.NextSnapshotDto(version = 3, next = null))
            val emitted = mutableListOf<NotificationDto.NotificationMessage>()
            rotationReconciler(rotation, principalHolding(Capability.CATALOG_READ)).reconcile { emitted += it }
            assertEquals(0, rotation.fetches)
            assertEquals(0, emitted.count { it is NotificationDto.RotationNextUpdateDto })
        }

    @Test
    fun `a failing rotation fetch does not escape reconcile`() =
        runTest {
            val rotation = RecordingRotationClient(snapshot = null)
            val emitted = mutableListOf<NotificationDto.NotificationMessage>()
            rotationReconciler(rotation, principalHolding(Capability.TABLE_READ)).reconcile { emitted += it }
            assertEquals(1, rotation.fetches)
            assertEquals(0, emitted.count { it is NotificationDto.RotationNextUpdateDto })
            assertEquals(1, emitted.count { it is NotificationDto.BillingRequestNotificationDto }, "earlier steps still ran")
        }
}
