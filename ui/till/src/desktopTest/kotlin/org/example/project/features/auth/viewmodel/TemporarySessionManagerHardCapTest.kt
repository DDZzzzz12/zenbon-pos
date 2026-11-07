package org.example.project.features.auth.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.features.auth.data.remote.TemporarySessionApiClient
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeTokenStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manager step-up has a server-side hard cap (15 minutes after the PIN entry). The till extends
 * on every navigation; once the server refuses because the cap is reached, the till must drop the
 * elevation itself and tell the cashier the manager approval expired — not keep showing a session
 * the server no longer honours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemporarySessionManagerHardCapTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTemporarySessionApi(
        var active: AuthDto.TemporarySessionResponseDto?,
    ) : TemporarySessionApiClient {
        var extendFailure: Throwable? = null
        var extendCalls = 0

        override suspend fun createSession(request: AuthDto.TemporarySessionRequestDto): AuthDto.TemporarySessionResponseDto = error("not used")

        override suspend fun getActiveSession(): AuthDto.TemporarySessionResponseDto? = active

        override suspend fun extendSession(sessionId: String): AuthDto.TemporarySessionResponseDto {
            extendCalls++
            extendFailure?.let { throw it }
            return active ?: error("no session")
        }

        override suspend fun endSession(sessionId: String) {
            active = null
        }
    }

    private fun session(
        remainingSeconds: Int = 120,
        expiresAt: String = "2026-09-26T12:10:00Z",
        hardCapAt: String? = "2026-09-26T12:15:00Z",
    ) = AuthDto.TemporarySessionResponseDto(
        sessionId = "SESSION-1",
        managerId = "m-1",
        managerName = "Mia Manager",
        managerRole = "MANAGER",
        deviceId = "POS-1",
        requestingEmployeeId = "e-1",
        expiresAt = expiresAt,
        remainingSeconds = remainingSeconds,
        hardCapAt = hardCapAt,
    )

    private fun manager(api: TemporarySessionApiClient) =
        TemporarySessionManager(
            api,
            PermissionManager(FakeLocalDatabaseManager.create(), FakeTokenStore.create()),
            FakeAuthApiClient(),
        )

    @Test
    fun `a navigation extend refused at the hard cap ends the elevation and raises the notice`() {
        val api = FakeTemporarySessionApi(session())
        val vm = manager(api)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.hasActiveSession, "precondition: the step-up is active")

        api.extendFailure =
            PosApiException(PosErrorCode.AUTH_STEP_UP_CAP_REACHED, 409, "cap reached", traceId = null)
        vm.trackNavigationChange()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, api.extendCalls)
        val state = vm.uiState.value
        assertFalse(state.hasActiveSession, "the till must drop an elevation the server ended")
        assertNull(state.activeSession)
        assertTrue(state.approvalExpiredNotice, "the cashier must be told the manager approval expired")

        vm.consumeApprovalExpiredNotice()
        assertFalse(vm.uiState.value.approvalExpiredNotice, "the notice is shown once")
    }

    @Test
    fun `any other extend failure keeps the elevation and shows no notice`() {
        val api = FakeTemporarySessionApi(session())
        val vm = manager(api)
        dispatcher.scheduler.runCurrent()

        api.extendFailure = NetworkApiException(java.io.IOException("offline"))
        vm.trackNavigationChange()
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.hasActiveSession, "a network blip is not the end of the approval")
        assertFalse(vm.uiState.value.approvalExpiredNotice)
    }

    @Test
    fun `a session pinned at its hard cap raises the notice when the countdown runs out`() {
        val cap = "2026-09-26T12:15:00Z"
        val api = FakeTemporarySessionApi(session(remainingSeconds = 2, expiresAt = cap, hardCapAt = cap))
        val vm = manager(api)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.isAtHardCap)

        dispatcher.scheduler.advanceTimeBy(3_500)
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession)
        assertTrue(vm.uiState.value.approvalExpiredNotice)
    }

    @Test
    fun `an ordinary idle expiry below the cap also raises the notice, worded as a time-out`() {
        val api = FakeTemporarySessionApi(session(remainingSeconds = 2))
        val vm = manager(api)
        dispatcher.scheduler.runCurrent()

        dispatcher.scheduler.advanceTimeBy(3_500)
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession)
        assertTrue(vm.uiState.value.approvalExpiredNotice, "the operator must be told the manager sign-in ran out")
        assertFalse(vm.uiState.value.approvalExpiredAtCap, "an idle timeout is not the hard cap")
    }

    @Test
    fun `ending the session by hand raises no notice`() {
        val api = FakeTemporarySessionApi(session())
        val vm = manager(api)
        dispatcher.scheduler.runCurrent()

        vm.endSession()
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession)
        assertFalse(vm.uiState.value.approvalExpiredNotice, "the operator ended it themselves")
    }
}
