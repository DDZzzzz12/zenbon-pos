package org.example.project.features.auth.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.features.auth.data.remote.TemporarySessionApiClient
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeTokenStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A manager step-up belongs to the person it was lent to (browser pass 1, console section).
 *
 * The manager is a root-scoped ViewModel, so it outlives the till's sign-out: K001's step-up kept
 * its countdown and its server sync running after K001 signed out, and once M001 signed in every
 * navigation tried to extend K001's session — "Failed to extend session: AUTH_FORBIDDEN", twice.
 * A sign-out, or a different person signing in, ends the elevation on this till; nothing about it
 * may reach the server afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemporarySessionManagerSignOutTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var localDb: LocalDatabaseManager
    private lateinit var tokenStore: TokenStore
    private lateinit var permissionManager: PermissionManager

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        localDb = FakeLocalDatabaseManager.create()
        tokenStore = FakeTokenStore.create()
        permissionManager = PermissionManager(localDb, tokenStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTemporarySessionApi(
        var active: AuthDto.TemporarySessionResponseDto?,
    ) : TemporarySessionApiClient {
        var extendCalls = 0
        var activeSessionCalls = 0
        var endCalls = 0

        /** When set, an extend waits on it — a request still in flight. */
        var extendGate: CompletableDeferred<Unit>? = null

        override suspend fun createSession(request: AuthDto.TemporarySessionRequestDto): AuthDto.TemporarySessionResponseDto = error("not used")

        override suspend fun getActiveSession(): AuthDto.TemporarySessionResponseDto? {
            activeSessionCalls++
            return active
        }

        override suspend fun extendSession(sessionId: String): AuthDto.TemporarySessionResponseDto {
            extendCalls++
            extendGate?.await()
            return session(remainingSeconds = 300)
        }

        override suspend fun endSession(sessionId: String) {
            endCalls++
            active = null
        }
    }

    private companion object {
        fun session(remainingSeconds: Int = 120) =
            AuthDto.TemporarySessionResponseDto(
                sessionId = "SESSION-K001",
                managerId = "m-1",
                managerName = "Mia Manager",
                managerRole = "MANAGER",
                deviceId = "POS-1",
                requestingEmployeeId = "e-k001",
                expiresAt = "2026-10-22T12:10:00Z",
                remainingSeconds = remainingSeconds,
                hardCapAt = "2026-10-22T12:15:00Z",
            )

        @OptIn(ExperimentalEncodingApi::class)
        fun jwt(employeeNumber: String): String {
            val payload = """{"sub":"id-$employeeNumber","employeeNumber":"$employeeNumber","role":"CASHIER"}"""
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
            return "eyJhbGciOiJub25lIn0.$encoded.sig"
        }
    }

    /** What a login leaves behind: the stored person and token, and the role loaded from them. */
    private fun signIn(employeeNumber: String) {
        localDb.setUserInfo(
            EmployeeInfoDto.EmployeeInfo(
                id = "id-$employeeNumber",
                username = employeeNumber.lowercase(),
                employeeNumber = employeeNumber,
                role = "CASHIER",
                language = "de",
                trainingMode = false,
                capabilities = emptySet(),
            ),
        )
        tokenStore.accessToken = jwt(employeeNumber)
        permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
    }

    /** What a logout does to the permission state (`LoginViewModel.logout`, the terminal session reset). */
    private fun signOut() {
        permissionManager.clearRole()
        tokenStore.clear()
    }

    private fun elevatedManagerFor(
        employeeNumber: String,
        api: FakeTemporarySessionApi,
    ): TemporarySessionManager {
        signIn(employeeNumber)
        val vm = TemporarySessionManager(api, permissionManager, FakeAuthApiClient())
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.hasActiveSession, "precondition: $employeeNumber holds a step-up")
        return vm
    }

    @Test
    fun `signing out ends the elevation and nothing reaches the server afterwards`() {
        val api = FakeTemporarySessionApi(session())
        val vm = elevatedManagerFor("K001", api)
        val syncCallsBefore = api.activeSessionCalls

        signOut()
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession, "a sign-out must end the elevation on this till")
        assertNull(vm.uiState.value.activeSession)

        vm.trackNavigationChange()
        vm.extendSession()
        dispatcher.scheduler.advanceTimeBy(5 * 60_000L)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, api.extendCalls, "no extend may be sent for a signed-out person's step-up")
        assertEquals(syncCallsBefore, api.activeSessionCalls, "the server sync must stop with the sign-out")
        assertEquals(0, api.endCalls, "the session is gone with its owner; the till does not call the server for it")
        assertFalse(vm.uiState.value.approvalExpiredNotice, "a sign-out is not an expired approval")
    }

    @Test
    fun `another person signing in never extends the previous person's step-up`() {
        val api = FakeTemporarySessionApi(session())
        val vm = elevatedManagerFor("K001", api)

        // Straight to the next person, without the role being cleared in between.
        signIn("M001")
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession, "M001 must not inherit K001's step-up")

        vm.trackNavigationChange()
        dispatcher.scheduler.advanceTimeBy(5 * 60_000L)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, api.extendCalls, "M001's navigation must not extend K001's session")
    }

    @Test
    fun `a switch between two people without a stored employee number is still a switch`() {
        // Only the tokens differ: nothing about either person is in local storage, so both have a
        // null employee number. Keyed on that number, K001 and M001 used to be the same person.
        val api = FakeTemporarySessionApi(session())
        localDb.setPreference("employee_role", "CASHIER")
        tokenStore.accessToken = jwt("K001")
        permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
        assertNull(permissionManager.currentEmployeeNumber.value, "precondition: no stored employee number")
        val vm = TemporarySessionManager(api, permissionManager, FakeAuthApiClient())
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.hasActiveSession, "precondition: K001 holds a step-up")

        tokenStore.accessToken = jwt("M001")
        permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession, "M001 must not inherit K001's step-up")
        vm.trackNavigationChange()
        dispatcher.scheduler.runCurrent()
        assertEquals(0, api.extendCalls, "M001's navigation must not extend K001's session")
    }

    @Test
    fun `an extend still in flight at sign-out does not revive the step-up`() {
        val api = FakeTemporarySessionApi(session())
        val vm = elevatedManagerFor("K001", api)
        val gate = CompletableDeferred<Unit>()
        api.extendGate = gate

        vm.trackNavigationChange()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, api.extendCalls)

        signOut()
        dispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        dispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.hasActiveSession, "a late answer for the signed-out person must be dropped")
        assertEquals(0, vm.uiState.value.remainingSeconds)
    }

    @Test
    fun `the same person's role reload keeps the step-up`() {
        val api = FakeTemporarySessionApi(session())
        val vm = elevatedManagerFor("K001", api)

        // A token refresh re-reads the same person; that is not a sign-out.
        signIn("K001")
        dispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.hasActiveSession)
        vm.trackNavigationChange()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, api.extendCalls)
    }
}
