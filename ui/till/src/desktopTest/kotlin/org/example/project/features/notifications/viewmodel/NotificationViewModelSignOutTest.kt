package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.NotificationConnection
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.translations.ChineseCommonStrings
import org.example.project.core.presentation.strings.translations.ChineseDashboardStrings
import org.example.project.core.presentation.strings.translations.ChineseFeaturesStrings
import org.example.project.core.presentation.strings.translations.ChineseRulesStrings
import org.example.project.core.presentation.strings.translations.ChineseSettingsStrings
import org.example.project.core.presentation.strings.translations.EnglishCommonStrings
import org.example.project.core.presentation.strings.translations.EnglishDashboardStrings
import org.example.project.core.presentation.strings.translations.EnglishFeaturesStrings
import org.example.project.core.presentation.strings.translations.EnglishRulesStrings
import org.example.project.core.presentation.strings.translations.EnglishSettingsStrings
import org.example.project.core.presentation.strings.translations.GermanCommonStrings
import org.example.project.core.presentation.strings.translations.GermanDashboardStrings
import org.example.project.core.presentation.strings.translations.GermanFeaturesStrings
import org.example.project.core.presentation.strings.translations.GermanRulesStrings
import org.example.project.core.presentation.strings.translations.GermanSettingsStrings
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.testutils.fakes.FakeLocalDatabaseManager
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.noOpTableStatusCacheWriter
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
 * One person's notifications are not shown to the next (G review, I2, the symmetric case).
 *
 * The ViewModel is root-scoped and outlives a sign-out. Its list held what the server addressed to
 * the previous person — billing requests, a manager's approval requests — and the next person to
 * sign in saw them, badge included. The list, the selection and the open panel now go with the
 * person; the stream and TSE footer state belong to the device and stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelSignOutTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tokenStore: TokenStore
    private lateinit var permissionManager: PermissionManager

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(
            mapOf(
                Language.GERMAN to GermanCommonStrings.all + GermanDashboardStrings.all +
                    GermanSettingsStrings.all + GermanFeaturesStrings.all + GermanRulesStrings.all,
                Language.ENGLISH to EnglishCommonStrings.all + EnglishDashboardStrings.all +
                    EnglishSettingsStrings.all + EnglishFeaturesStrings.all + EnglishRulesStrings.all,
                Language.CHINESE to ChineseCommonStrings.all + ChineseDashboardStrings.all +
                    ChineseSettingsStrings.all + ChineseFeaturesStrings.all + ChineseRulesStrings.all,
            ),
        )
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

    private companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun jwt(employeeNumber: String): String {
            val payload = """{"sub":"id-$employeeNumber","employeeNumber":"$employeeNumber","role":"MANAGER"}"""
            val encoded = Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')
            return "eyJhbGciOiJub25lIn0.$encoded.sig"
        }

        fun billingRequest(tableId: Long) =
            NotificationDto.BillingRequestNotificationDto(
                timestamp = "2026-10-22T12:0$tableId:00Z",
                tableId = tableId,
                tableNumber = tableId.toString(),
                requestingEmployeeNumber = "K002",
            )
    }

    private fun signIn(employeeNumber: String) {
        tokenStore.accessToken = jwt(employeeNumber)
        permissionManager.readRoleSnapshot()?.let(permissionManager::applyRoleSnapshot)
    }

    private fun signOut() {
        permissionManager.clearRole()
        tokenStore.clear()
    }

    private fun viewModel(connection: FakeNotificationConnection) =
        NotificationViewModel(
            connection,
            LanguageManager(),
            FakeSyncService(),
            noOpTableStatusCacheWriter(),
            FakeOverlayEventBus(),
            tokenStore,
            permissionManager = permissionManager,
        )

    @Test
    fun `signing out clears the person's notifications but keeps the device state`() =
        runTest(testDispatcher) {
            signIn("M001")
            val connection = FakeNotificationConnection()
            val vm = viewModel(connection)
            connection.emitConnectionState(NotificationConnection.ConnectionState.Connected)
            advanceUntilIdle()
            connection.emitMessage(billingRequest(1))
            connection.emitMessage(billingRequest(2))
            advanceUntilIdle()
            vm.showNotificationPanel()
            val first = vm.uiState.value.notifications[0]
            vm.selectNotification(first)
            assertEquals(2, vm.uiState.value.notifications.size, "precondition: M001 has notifications")

            signOut()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.notifications.isEmpty(), "the next person must not see M001's notifications")
            assertEquals(0, state.unreadCount, "nor M001's badge")
            assertNull(state.selectedNotification)
            assertFalse(state.showNotificationPanel)
            assertEquals(ConnectionState.Connected, state.connectionState, "the stream state belongs to the device")
        }

    @Test
    fun `another person signing in without a sign-out also starts empty`() =
        runTest(testDispatcher) {
            signIn("M001")
            val connection = FakeNotificationConnection()
            val vm = viewModel(connection)
            advanceUntilIdle()
            connection.emitMessage(billingRequest(1))
            advanceUntilIdle()

            signIn("K001")
            advanceUntilIdle()

            val afterSwitch = vm.uiState.value.notifications
            assertTrue(afterSwitch.isEmpty(), "K001 must not see M001's notifications")
            assertEquals(0, vm.uiState.value.unreadCount)

            // What arrives for K001 afterwards is kept.
            connection.emitMessage(billingRequest(3))
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.notifications.size)
        }

    @Test
    fun `the same person's token refresh keeps the notifications`() =
        runTest(testDispatcher) {
            signIn("M001")
            val connection = FakeNotificationConnection()
            val vm = viewModel(connection)
            advanceUntilIdle()
            connection.emitMessage(billingRequest(1))
            advanceUntilIdle()

            signIn("M001")
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.notifications.size)
        }
}
