package org.example.project.features.auth.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.features.auth.presentation.state.ApprovalDialogState
import org.example.project.features.auth.presentation.state.ApprovalRequestUiState
import org.example.project.features.auth.presentation.viewmodels.ApprovalRequestViewModel
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeTokenStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A manager's approval requests stay with that manager (G review, I2).
 *
 * `MainScreen` obtains the ViewModel from the root ViewModelStore, so it outlives a sign-out: M001's
 * pending requests and open approval dialog were shown to the cashier who signed in next. The
 * server refuses that cashier's approve/deny with 403, so it leaked data without granting anything.
 * A sign-out, or another person signing in, now leaves the next person with an empty state; what
 * their role entitles them to arrives again from the server (the reconciler replays on connect).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalRequestViewModelSignOutTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var tokenStore: TokenStore
    private lateinit var permissionManager: PermissionManager

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tokenStore = FakeTokenStore.create()
        // What a login stores besides the token; the person's identity comes from the token alone.
        val localDb = FakeLocalDatabaseManager.create()
        localDb.setPreference("employee_role", "MANAGER")
        permissionManager = PermissionManager(localDb, tokenStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeApprovalApi : ApprovalRequestApiClient {
        var pending: List<AuthDto.ApprovalRequestDto> = emptyList()
        var pendingFetches = 0

        /** When set, an approve waits on it — a request still in flight. */
        var approveGate: CompletableDeferred<Unit>? = null

        override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> {
            pendingFetches++
            return pending
        }

        override suspend fun approveRequest(requestId: String): AuthDto.ApprovalResponseDto {
            approveGate?.await()
            return AuthDto.ApprovalResponseDto(requestId = requestId, approved = true, approvingManagerId = "id-M001")
        }

        override suspend fun denyRequest(
            requestId: String,
            reason: String,
        ): AuthDto.ApprovalResponseDto = AuthDto.ApprovalResponseDto(requestId = requestId, approved = false, approvingManagerId = "id-M001")

        override suspend fun cancelRequest(requestId: String) {}

        override suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto =
            AuthDto.RequestStatusDto(requestId = requestId, status = AuthDto.RequestStatus.PENDING)
    }

    private companion object {
        fun request(id: String) =
            AuthDto.ApprovalRequestDto(
                requestId = id,
                requestingEmployeeId = "id-K002",
                requestingEmployeeNumber = "K002",
                deviceId = "POS-2",
                operation = "CANCEL_ORDER_ITEMS",
                requiredCapabilities = setOf("ORDER_CANCEL"),
                details = mapOf("table" to "4"),
                timestamp = "2026-10-22T12:00:00Z",
            )

        @OptIn(ExperimentalEncodingApi::class)
        fun jwt(employeeNumber: String): String {
            val payload = """{"sub":"id-$employeeNumber","employeeNumber":"$employeeNumber","role":"MANAGER"}"""
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
            return "eyJhbGciOiJub25lIn0.$encoded.sig"
        }
    }

    private fun signIn(employeeNumber: String) {
        tokenStore.accessToken = jwt(employeeNumber)
        permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
    }

    private fun signOut() {
        permissionManager.clearRole()
        tokenStore.clear()
    }

    /** M001 signed in, with one pending request on the list and its dialog open. */
    private fun managerWithOpenRequest(api: FakeApprovalApi): ApprovalRequestViewModel {
        signIn("M001")
        val vm = ApprovalRequestViewModel(api, permissionManager)
        dispatcher.scheduler.runCurrent()
        api.pending = listOf(request("REQ-1"), request("REQ-2"))
        vm.loadPendingRequests()
        dispatcher.scheduler.runCurrent()
        vm.handleIncomingApprovalRequest(request("REQ-3"))
        dispatcher.scheduler.runCurrent()
        assertEquals(3, vm.uiState.value.pendingRequests.size, "precondition: M001 has pending requests")
        assertEquals(ApprovalDialogState.ShowRequest(request("REQ-3")), vm.uiState.value.currentDialog, "precondition: dialog open")
        return vm
    }

    @Test
    fun `signing out leaves nothing of the manager's requests for the next person`() {
        val api = FakeApprovalApi()
        val vm = managerWithOpenRequest(api)

        signOut()
        dispatcher.scheduler.runCurrent()
        assertEquals(ApprovalRequestUiState(), vm.uiState.value, "a sign-out must clear the requests and close the dialog")

        signIn("K001")
        dispatcher.scheduler.advanceTimeBy(5 * 60_000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(ApprovalRequestUiState(), vm.uiState.value, "K001 must start empty")
        assertEquals(1, api.pendingFetches, "nothing may fetch on the previous person's behalf afterwards")
    }

    @Test
    fun `another person signing in without a sign-out also starts empty`() {
        val vm = managerWithOpenRequest(FakeApprovalApi())

        signIn("K001")
        dispatcher.scheduler.runCurrent()

        assertEquals(ApprovalRequestUiState(), vm.uiState.value, "K001 must not see M001's requests or dialog")
    }

    @Test
    fun `an approve still in flight at sign-out does not touch the next person's state`() {
        val api = FakeApprovalApi()
        val vm = managerWithOpenRequest(api)
        val gate = CompletableDeferred<Unit>()
        api.approveGate = gate
        var onSuccessCalls = 0

        vm.approveRequest("REQ-3") { onSuccessCalls++ }
        dispatcher.scheduler.runCurrent()
        signOut()
        dispatcher.scheduler.runCurrent()
        signIn("K001")
        vm.handleIncomingApprovalRequest(request("REQ-9"))
        dispatcher.scheduler.runCurrent()
        val before = vm.uiState.value

        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertEquals(before, vm.uiState.value, "a late answer for M001 must not change K001's state")
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(0, onSuccessCalls, "M001's success callback must not run for K001")
    }

    @Test
    fun `the same person's token refresh keeps the requests`() {
        val vm = managerWithOpenRequest(FakeApprovalApi())

        signIn("M001")
        dispatcher.scheduler.runCurrent()

        assertEquals(3, vm.uiState.value.pendingRequests.size)
        assertEquals(ApprovalDialogState.ShowRequest(request("REQ-3")), vm.uiState.value.currentDialog)
    }
}
