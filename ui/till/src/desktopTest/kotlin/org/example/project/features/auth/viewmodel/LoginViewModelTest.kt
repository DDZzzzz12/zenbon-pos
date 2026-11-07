package org.example.project.features.auth.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.TokenResponse
import org.example.project.core.auth.RoleSnapshot
import org.example.project.core.auth.SessionStatus
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.database.secret.SealedSecret
import org.example.project.core.data.database.secret.SecretCipher
import org.example.project.core.data.database.secret.SecretProtectingKeyValueStore
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.provider.RoomElementDataProvider
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.serverDetailFallback
import org.example.project.core.presentation.strings.keys.LoginKeys
import org.example.project.features.auth.domain.usecase.LoginUseCase
import org.example.project.features.auth.presentation.state.RememberedLogin
import org.example.project.features.auth.presentation.viewmodels.LoginViewModel
import org.example.project.features.auth.presentation.viewmodels.ServerStatus
import org.example.project.testutils.fakes.FakeAuthApiClient
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakePlatformNotificationService
import org.example.project.testutils.fakes.FakeSecretCipher
import org.example.project.testutils.fakes.FakeSetupApiClient
import org.example.project.testutils.fakes.FakeTerminalRegistrationService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.IoMarkingDispatcher
import org.example.project.testutils.fakes.SecretAccessRecorder
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Where the use case and view model run storage; the test dispatcher unless a test records it. */
    private var ioDispatcher: CoroutineDispatcher = testDispatcher

    private lateinit var fakeAuthApiClient: FakeAuthApiClient
    private lateinit var fakeTerminalRegistrationService: FakeTerminalRegistrationService
    private lateinit var fakeSetupApiClient: FakeSetupApiClient
    private lateinit var localDB: org.example.project.core.data.database.LocalDatabaseManager
    private lateinit var permissionManager: org.example.project.core.auth.PermissionManager
    private lateinit var tokenStore: org.example.project.core.auth.TokenStore
    private lateinit var sessionState: SessionState
    private lateinit var fakePlatformNotificationService: FakePlatformNotificationService
    private lateinit var fakeNotificationConnection: FakeNotificationConnection
    private lateinit var loginUseCase: LoginUseCase
    private lateinit var viewModel: LoginViewModel

    companion object {
        private fun createSuccessLoginResponse(
            language: String = "de",
            accessToken: String = "fake-access-token",
            role: String = "ADMIN",
            capabilities: Set<String> = emptySet(),
            refreshToken: String? = "fake-refresh-token",
        ): LoginResponse =
            LoginResponse(
                token =
                    TokenResponse(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresIn = 3600,
                    ),
                employee =
                    EmployeeInfoDto.EmployeeInfo(
                        id = "test-employee-id",
                        username = "testuser",
                        employeeNumber = "EMP001",
                        role = role,
                        language = language,
                        trainingMode = false,
                        capabilities = capabilities,
                    ),
            )

        /**
         * An unsigned but realistically shaped token: `PermissionManager.loadRoleFromToken` bails on a
         * token it cannot decode, so the opaque "fake-access-token" would never resolve a policy.
         */
        @OptIn(ExperimentalEncodingApi::class)
        private fun decodableJwt(role: String): String {
            val payload = """{"sub":"test-employee-id","employeeNumber":"EMP001","role":"$role"}"""
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
            return "eyJhbGciOiJub25lIn0.$encoded.sig"
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakeAuthApiClient = FakeAuthApiClient()
        fakeTerminalRegistrationService = FakeTerminalRegistrationService()
        fakeSetupApiClient = FakeSetupApiClient()
        fakePlatformNotificationService = FakePlatformNotificationService()
        fakeNotificationConnection = FakeNotificationConnection()
        localDB = FakeLocalDatabaseManager.create()
        tokenStore = FakeTokenStore.create()
        sessionState = SessionState(tokenStore, StartupPolicy.WIPE)
        permissionManager = FakePermissionManager.create(localDB, tokenStore)
        loginUseCase = newLoginUseCase()
    }

    /** A use case over the current [tokenStore] and [localDB]. */
    private fun newLoginUseCase() =
        LoginUseCase(
            fakeAuthApiClient,
            tokenStore,
            localDB,
            fakeTerminalRegistrationService,
            noOpRTableDataProvider(),
            noOpRoomElementDataProvider(),
            ioDispatcher = ioDispatcher,
        )

    /**
     * Rebuilds the session pieces over one sealing store, wired as the till wires them, and returns the raw
     * rows underneath it.
     */
    private fun useSealedStorage(cipher: SecretCipher = FakeSecretCipher()): FakeKeyValueStore {
        val raw = FakeKeyValueStore()
        useStore(SecretProtectingKeyValueStore(raw, cipher))
        return raw
    }

    /**
     * Like [useSealedStorage], but storage runs on a marking IO dispatcher and every secret read and write is
     * recorded, so a test can prove none of them ran on the UI thread.
     */
    private fun useRecordedSealedStorage(): SecretAccessRecorder {
        val io = IoMarkingDispatcher(testDispatcher)
        ioDispatcher = io
        val recorder = SecretAccessRecorder(SecretProtectingKeyValueStore(FakeKeyValueStore(), FakeSecretCipher()), io)
        useStore(recorder)
        return recorder
    }

    private fun useStore(store: KeyValueStore) {
        localDB = LocalDatabaseManager(store)
        tokenStore = TokenStore(store)
        sessionState = SessionState(tokenStore, StartupPolicy.WIPE)
        permissionManager = FakePermissionManager.create(localDB, tokenStore)
        loginUseCase = newLoginUseCase()
    }

    private fun noOpRTableDataProvider() =
        object : RTableDataProvider {
            override suspend fun getAllRTables(): List<RTable> = emptyList()

            override suspend fun getRTablesByLocationTypeId(
                locationTypeId: Long,
                includeInactive: Boolean,
            ): List<RTable> = emptyList()

            override suspend fun getRTableById(id: Long): RTable? = null

            override suspend fun searchRTables(query: String): List<RTable> = emptyList()

            override suspend fun refresh(
                locationTypeId: Long,
                includeInactive: Boolean,
            ) {}

            override suspend fun ensureSeeded(
                locationTypeId: Long,
                includeInactive: Boolean,
            ) {}

            override fun resetSeed() {}
        }

    private fun noOpRoomElementDataProvider() =
        object : RoomElementDataProvider {
            override suspend fun getAllElements(): List<RoomElement> = emptyList()

            override suspend fun getElementsByLocationType(locationType: String): List<RoomElement> = emptyList()

            override suspend fun refresh(locationTypeId: Long) {}

            override suspend fun ensureSeeded(locationTypeId: Long) {}

            override fun resetSeed() {}
        }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LoginViewModel {
        val vm =
            LoginViewModel(
                loginUseCase = loginUseCase,
                localDB = localDB,
                permissionManager = permissionManager,
                tokenStore = tokenStore,
                setupApiClient = fakeSetupApiClient,
                platformNotificationService = fakePlatformNotificationService,
                sessionState = sessionState,
                notificationConnection = fakeNotificationConnection,
                ioDispatcher = ioDispatcher,
            )
        // Cancel the init-launched health-check loop so advanceUntilIdle() drains only
        // finite tasks. The unbounded while(isActive){ checkServerHealth(); delay(5000) }
        // loop otherwise spins advanceUntilIdle (TestMainDispatcher.NonConcurrentlyModifiable)
        // for tests that never reach a successful-login stopHealthCheck() call. No test
        // asserts on health-check behavior.
        vm.stopHealthCheck()
        return vm
    }

    @Nested
    inner class Login {
        @Test
        fun `blank identifier sets error`() =
            runTest(testDispatcher) {
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("", "password", false) {}

                val error = viewModel.uiState.value.error
                assertNotNull(error)
                assertEquals(LoginKeys.errorFillAllFields, error.messageKey)
            }

        @Test
        fun `blank secret sets error`() =
            runTest(testDispatcher) {
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "", false) {}

                val error = viewModel.uiState.value.error
                assertNotNull(error)
                assertEquals(LoginKeys.errorFillAllFields, error.messageKey)
            }

        @Test
        fun `successful login sets isAuthenticated`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()

                var successLanguage: String? = null
                viewModel.login("EMP001", "password123", false) { language ->
                    successLanguage = language
                }
                advanceUntilIdle()

                assertEquals(SessionStatus.AUTHENTICATED, sessionState.status)
                assertEquals("de", successLanguage)
            }

        @Test
        fun `the reported capabilities are resolved before the notification service starts`() =
            runTest(testDispatcher) {
                // NotificationReconciler reads the policy once, right after the SSE handshake. If the
                // stream opened before the policy was loaded, a manager's pending approvals were
                // skipped until the next reconnect.
                fakeAuthApiClient.loginResult =
                    Result.success(
                        createSuccessLoginResponse(
                            accessToken = decodableJwt("MANAGER"),
                            role = "MANAGER",
                            capabilities = setOf(Capability.SESSION_READ.name),
                        ),
                    )
                var sessionReadAtStart: Boolean? = null
                fakePlatformNotificationService.onStartService = {
                    sessionReadAtStart = permissionManager.accessPolicy.value.allows(Capability.SESSION_READ)
                }
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                assertEquals(1, fakePlatformNotificationService.startServiceCallCount)
                assertEquals(true, sessionReadAtStart)
            }

        /**
         * Android's notification service streams only for an AUTHENTICATED session and stops otherwise (NOTE-8,
         * emulator pass 5), so the session must already be logged in when the login starts it.
         */
        @Test
        fun `the session is authenticated when the notification service starts`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                var statusAtStart: SessionStatus? = null
                fakePlatformNotificationService.onStartService = { statusAtStart = sessionState.status }
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                assertEquals(1, fakePlatformNotificationService.startServiceCallCount)
                assertEquals(SessionStatus.AUTHENTICATED, statusAtStart)
            }

        @Test
        fun `an unreported capability set falls back to the stored role before the service starts`() =
            runTest(testDispatcher) {
                // A server too old to report capabilities: the policy resolves from the role, which must
                // be loaded by the same point.
                fakeAuthApiClient.loginResult =
                    Result.success(
                        createSuccessLoginResponse(accessToken = decodableJwt("MANAGER"), role = "MANAGER"),
                    )
                var sessionReadAtStart: Boolean? = null
                fakePlatformNotificationService.onStartService = {
                    sessionReadAtStart = permissionManager.accessPolicy.value.allows(Capability.SESSION_READ)
                }
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                assertEquals(true, sessionReadAtStart)
            }

        @Test
        fun `failed login sets error`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult =
                    Result.failure(
                        NetworkApiException(IllegalStateException("simulated network failure")),
                    )
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                val error = viewModel.uiState.value.error
                assertNotNull(error)
                assertIs<UiError.Network>(error)
            }

        @Test
        fun `rejected credentials surface a localized invalid-credentials error`() =
            runTest(testDispatcher) {
                // The server answers a wrong password with 401 AUTH_INVALID_CREDENTIALS. Unmapped,
                // that fell through to the generic unknown-error key and displayMessage() then
                // showed the server's untranslated "Invalid credentials".
                fakeAuthApiClient.loginResult =
                    Result.failure(
                        PosApiException(
                            code = PosErrorCode.AUTH_INVALID_CREDENTIALS,
                            httpStatus = 401,
                            message = "Invalid credentials",
                            traceId = "t-1",
                        ),
                    )
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "wrong-password", false) {}
                advanceUntilIdle()

                val error = viewModel.uiState.value.error
                assertNotNull(error)
                assertIs<UiError.FromServer>(error)
                assertEquals(PosErrorCode.AUTH_INVALID_CREDENTIALS, error.code)
                assertEquals(LoginKeys.errorInvalidCredentials, error.messageKey)
                // A mapped key must win over the server's own untranslated detail.
                assertNull(error.serverDetailFallback())
            }

        @Test
        fun `a session that cannot be stored locally fails the login without crashing or half-authenticating`() =
            runTest(testDispatcher) {
                // The access token seals, then sealing the refresh token throws: a half-stored token pair.
                val cipher = FakeSecretCipher()
                val refreshTokenSealFails =
                    object : SecretCipher by cipher {
                        override fun seal(
                            key: String,
                            plaintext: String,
                        ): String {
                            check(key != "jwt_refresh_token") { "seal unavailable" }
                            return cipher.seal(key, plaintext)
                        }
                    }
                tokenStore = TokenStore(SecretProtectingKeyValueStore(FakeKeyValueStore(), refreshTokenSealFails))
                sessionState = SessionState(tokenStore, StartupPolicy.WIPE)
                loginUseCase = newLoginUseCase()
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()

                var succeeded = false
                viewModel.login("EMP001", "password123", rememberMe = true) { succeeded = true }
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertIs<UiError.Unknown>(state.error)
                assertFalse(state.isLoading)
                assertFalse(state.requiresPairing)
                assertFalse(succeeded)
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
                assertNull(tokenStore.accessToken, "a half-stored token pair must not survive")
                assertNull(tokenStore.refreshToken)
            }

        @Test
        fun `a login response without a refresh token fails the login instead of throwing`() =
            runTest(testDispatcher) {
                // Saved by an earlier login. A response the server got wrong is no reason to forget them.
                localDB.setLoginUserData("OLD-USER", "old-password", rememberMe = true)
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de", refreshToken = null))
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", rememberMe = true) {}
                advanceUntilIdle()

                assertIs<UiError.Unknown>(viewModel.uiState.value.error)
                assertFalse(viewModel.uiState.value.isLoading)
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
                assertNull(tokenStore.accessToken, "no part of a session without a refresh token is stored")
                assertEquals("OLD-USER", localDB.getLoginUserData(), "earlier remember-me credentials must survive")
                assertEquals("old-password", localDB.getLoginPassword(), "earlier remember-me credentials must survive")
                assertEquals(true, localDB.getLoginRememberMe())

                // The use case itself returns the failure rather than throwing it.
                val result = loginUseCase("EMP001", "password123")
                assertIs<IllegalArgumentException>(result.exceptionOrNull())
            }
    }

    @Nested
    inner class RememberMe {
        @Test
        fun `remember-me on saves the credentials, with the password sealed`() =
            runTest(testDispatcher) {
                val raw = useSealedStorage()
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", rememberMe = true) {}
                advanceUntilIdle()

                assertEquals(SessionStatus.AUTHENTICATED, sessionState.status)
                assertEquals("EMP001", localDB.getLoginUserData())
                assertEquals("password123", localDB.getLoginPassword())
                assertEquals(true, localDB.getLoginRememberMe())
                val stored = assertNotNull(raw.getString("password"))
                assertTrue(SealedSecret.isSealed(stored), "the remember-me password must not be stored in plaintext")
            }

        @Test
        fun `remember-me off clears credentials an earlier login saved`() =
            runTest(testDispatcher) {
                useSealedStorage()
                localDB.setLoginUserData("OLD-USER", "old-password", rememberMe = true)
                assertEquals("old-password", localDB.getLoginPassword(), "precondition")
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", rememberMe = false) {}
                advanceUntilIdle()

                assertEquals(SessionStatus.AUTHENTICATED, sessionState.status)
                assertTrue(localDB.getLoginUserData().isNullOrEmpty())
                assertTrue(localDB.getLoginPassword().isNullOrEmpty())
                assertEquals(false, localDB.getLoginRememberMe())
            }
    }

    @Nested
    inner class OffTheUiThread {
        @Test
        fun `login seals the tokens and the remember-me password on the IO dispatcher`() =
            runTest(testDispatcher) {
                val recorder = useRecordedSealedStorage()
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()
                recorder.reset()

                viewModel.login("EMP001", "password123", rememberMe = true) {}
                advanceUntilIdle()

                assertEquals(SessionStatus.AUTHENTICATED, sessionState.status)
                assertTrue(
                    recorder.secretAccesses.containsAll(listOf("set:jwt_access_token", "set:jwt_refresh_token", "set:password")),
                    "expected the session secrets to be written, got ${recorder.secretAccesses}",
                )
                assertEquals(emptyList(), recorder.offIo, "a secret was sealed on the UI thread")
            }
    }

    @Nested
    inner class RememberedCredentials {
        @Test
        fun `remembered credentials load on the IO dispatcher`() =
            runTest(testDispatcher) {
                val recorder = useRecordedSealedStorage()
                localDB.setLoginUserData("EMP001", "password123", rememberMe = true)
                viewModel = createViewModel()
                advanceUntilIdle()
                recorder.reset()

                val remembered = viewModel.loadRememberedLogin()

                assertEquals(RememberedLogin("EMP001", "password123"), remembered)
                assertEquals(listOf("get:password"), recorder.secretAccesses)
                assertEquals(emptyList(), recorder.offIo, "the remembered password was opened on the UI thread")
            }

        @Test
        fun `nothing is pre-filled when remember-me is off`() =
            runTest(testDispatcher) {
                useSealedStorage()
                // A password left behind with the flag off must still not be offered.
                localDB.setLoginUserData("EMP001", "password123", rememberMe = true)
                localDB.setPreference("rememberMe", "false")
                viewModel = createViewModel()
                advanceUntilIdle()

                assertNull(viewModel.loadRememberedLogin())
            }

        /**
         * The view model lives as long as the app, so its state must never hold the password: a login screen that left
         * before taking it would leave it there, ready to pre-fill a password the user later asked to forget.
         */
        @Test
        fun `the remembered password goes to the caller and never into the ui state`() =
            runTest(testDispatcher) {
                useSealedStorage()
                localDB.setLoginUserData("EMP001", "password123", rememberMe = true)
                viewModel = createViewModel()
                advanceUntilIdle()

                val remembered = assertNotNull(viewModel.loadRememberedLogin())
                advanceUntilIdle()

                assertEquals("password123", remembered.secret)
                assertFalse(remembered.toString().contains("password123"), "a logged value must not carry the password")
                val loggedState = viewModel.uiState.value.toString()
                assertFalse(loggedState.contains("password123"), "the ui state must not carry it")
            }
    }

    @Nested
    inner class ClearError {
        @Test
        fun `clearError sets error to null`() =
            runTest(testDispatcher) {
                viewModel = createViewModel()
                advanceUntilIdle()

                // Trigger an error first
                viewModel.login("", "password", false) {}
                assertNotNull(viewModel.uiState.value.error)

                // Clear the error
                viewModel.clearError()

                assertNull(viewModel.uiState.value.error)
            }
    }

    @Nested
    inner class Pairing {
        @Test
        fun `login surfaces TERMINAL_NEEDS_PAIRING as requiresPairing without authenticating`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                fakeTerminalRegistrationService.ensureRegisteredThrows =
                    PosApiException(
                        code = PosErrorCode.TERMINAL_NEEDS_PAIRING,
                        httpStatus = 409,
                        message = "needs pairing",
                        traceId = null,
                    )
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                assertTrue(viewModel.uiState.value.requiresPairing)
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
            }
    }

    @Nested
    inner class Logout {
        @Test
        fun `logout clears authentication state`() =
            runTest(testDispatcher) {
                // First login successfully
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()
                assertEquals(SessionStatus.AUTHENTICATED, sessionState.status)

                // Now logout
                viewModel.logout()
                advanceUntilIdle()

                // After logout, the session is signed out
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
            }

        /**
         * A deliberate "Abmelden" must be silent (browser pass 1, bug 9). The browser and desktop
         * clients have no notification service to stop, so the stream stayed open through the
         * logout: the server ended it, its reconnect found the tokens already cleared, and the
         * resulting NO_ACCESS_TOKEN failure raised "Authentifizierungsfehler – An dieser Station ist
         * keine aktive Sitzung vorhanden". The stream has to be closed while the session is still
         * whole — before the server is told and before the tokens go.
         */
        @Test
        fun `logout closes the notification stream before the server logout and before the tokens are cleared`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                var tokenAtDisconnect: String? = null
                var serverLogoutsAtDisconnect: Int? = null
                fakeNotificationConnection.onDisconnect = {
                    tokenAtDisconnect = tokenStore.accessToken
                    serverLogoutsAtDisconnect = fakeAuthApiClient.logoutCallCount
                }

                viewModel.logout()
                advanceUntilIdle()

                assertEquals(1, fakeNotificationConnection.disconnectCount, "logout must close the notification stream")
                assertNotNull(tokenAtDisconnect, "the stream must be closed while the tokens are still stored")
                assertEquals(0, serverLogoutsAtDisconnect, "the stream must be closed before the server ends the session")
                assertEquals(1, fakeAuthApiClient.logoutCallCount)
                assertNull(tokenStore.accessToken)
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
            }

        /**
         * Closing the stream is the first step of the teardown, so a failure there must not take the
         * rest with it: the server would keep the session and the tokens would stay on the till.
         */
        @Test
        fun `logout still ends the session when closing the notification stream fails`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()
                fakeNotificationConnection.onDisconnect = { throw IllegalStateException("stream already torn down") }
                var completed = false

                viewModel.logout { completed = true }
                advanceUntilIdle()

                assertEquals(1, fakeNotificationConnection.disconnectCount)
                assertEquals(1, fakePlatformNotificationService.stopServiceCallCount, "the service must still be stopped")
                assertEquals(1, fakeAuthApiClient.logoutCallCount, "the server must still be told")
                assertNull(tokenStore.accessToken, "the tokens must still be cleared")
                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
                assertTrue(completed, "the caller waiting for the teardown must still be released")
            }

        @Test
        fun `clearRole drops the capability set with the role`() =
            runTest(testDispatcher) {
                // Left behind, a cashier logging in right after a manager briefly carried the manager's
                // capabilities.
                fakeAuthApiClient.loginResult =
                    Result.success(
                        createSuccessLoginResponse(
                            accessToken = decodableJwt("MANAGER"),
                            role = "MANAGER",
                            capabilities = setOf(Capability.SESSION_READ.name, Capability.ORDER_WRITE.name),
                        ),
                    )
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()
                assertTrue(permissionManager.accessPolicy.value.allows(Capability.SESSION_READ))

                permissionManager.clearRole()

                val policy = permissionManager.accessPolicy.value
                assertTrue(Capability.entries.none { policy.allows(it) }, "no capability may survive clearRole")
                assertFalse(policy.allows(Capability.SESSION_READ))
            }

        /**
         * Logout clears the role at once but the tokens only after the server call, so `SettingProvider`'s role read can
         * still find the access token and publish its snapshot in between. That role must not outlive the sign-out.
         */
        @Test
        fun `a role published while logout is still running does not outlive it`() =
            runTest(testDispatcher) {
                fakeAuthApiClient.loginResult = Result.success(createSuccessLoginResponse("de"))
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.login("EMP001", "password123", false) {}
                advanceUntilIdle()

                viewModel.logout()
                // A role read that started before the tokens were cleared resumes now, before the server call.
                permissionManager.applyRoleSnapshot(
                    RoleSnapshot(
                        role = EmployeeRole.ADMIN,
                        employeeNumber = "EMP001",
                        employeeName = null,
                        trainingMode = false,
                        capabilities = null,
                    ),
                )
                advanceUntilIdle()

                assertEquals(SessionStatus.UNAUTHENTICATED, sessionState.status)
                assertNull(permissionManager.currentRole.value, "a role published during logout outlived the sign-out")
            }
    }

    @Nested
    inner class HealthCheck {
        /**
         * Holds the next probe open, runs it, and reports the badge state *during* the call.
         * Deliberately not a StateFlow collector: StateFlow conflates, so a transient CHECKING can
         * be dropped and the flicker would go unobserved.
         */
        private suspend fun statusWhileProbeInFlight(advance: suspend () -> Unit): ServerStatus {
            val gate = CompletableDeferred<Unit>()
            fakeSetupApiClient.onHealthCheck = { gate.await() }

            viewModel.checkHealth()
            advance()
            val inFlight = viewModel.uiState.value.serverStatus

            gate.complete(Unit)
            advance()
            fakeSetupApiClient.onHealthCheck = null
            return inFlight
        }

        @Test
        fun `a poll against an already-connected server does not flash CHECKING`() =
            runTest(testDispatcher) {
                // checkServerHealth() set CHECKING unconditionally on entry, so the login badge
                // visibly flickered Online -> Checking -> Online on every 5 s poll against a
                // perfectly reachable server.
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.checkHealth()
                advanceUntilIdle()
                assertEquals(ServerStatus.CONNECTED, viewModel.uiState.value.serverStatus)

                val inFlight = statusWhileProbeInFlight { advanceUntilIdle() }

                assertEquals(
                    ServerStatus.CONNECTED,
                    inFlight,
                    "a re-check while connected must leave the badge Online, not flip it to CHECKING",
                )
                assertEquals(ServerStatus.CONNECTED, viewModel.uiState.value.serverStatus)
            }

        @Test
        fun `a retry while disconnected still reports CHECKING`() =
            runTest(testDispatcher) {
                // Only the connected case is suppressed. With the server down the badge must still
                // show that a retry is in flight, or the screen looks frozen.
                fakeSetupApiClient.healthCheckResult =
                    Result.failure(NetworkApiException(IllegalStateException("server down")))
                viewModel = createViewModel()
                advanceUntilIdle()
                viewModel.checkHealth()
                advanceUntilIdle()
                assertEquals(ServerStatus.DISCONNECTED, viewModel.uiState.value.serverStatus)

                val inFlight = statusWhileProbeInFlight { advanceUntilIdle() }

                assertEquals(ServerStatus.CHECKING, inFlight)
            }

        @Test
        fun `an authenticated session stops the poll, leaving it restarts it once`() =
            runTest(testDispatcher) {
                // The order station's session comes from the terminal exchange, never from login(),
                // so the poll login() stops kept hitting /health every 5 s on the station's dashboard.
                viewModel = createViewModel()
                viewModel.onSessionStatusChanged(SessionStatus.UNAUTHENTICATED)
                viewModel.onSessionStatusChanged(SessionStatus.UNAUTHENTICATED)
                runCurrent()
                assertEquals(1, fakeSetupApiClient.healthCheckCallCount, "a second UNAUTHENTICATED must not start a second loop")

                viewModel.onSessionStatusChanged(SessionStatus.AUTHENTICATED)
                advanceTimeBy(30_000)
                runCurrent()
                assertEquals(1, fakeSetupApiClient.healthCheckCallCount, "no poll while a session is open")

                viewModel.onSessionStatusChanged(SessionStatus.UNAUTHENTICATED)
                runCurrent()
                assertEquals(2, fakeSetupApiClient.healthCheckCallCount, "leaving the session resumes the poll")
                viewModel.stopHealthCheck()
            }
    }
}
