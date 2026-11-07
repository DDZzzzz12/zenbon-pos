package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.network.NotificationConnection
import org.example.project.core.utils.LanguageManager
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.testutils.fakes.FakeNotificationConnection
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.testutils.fakes.noOpTableStatusCacheWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression test for the "kicked back to login right after re-login" bug.
 *
 * The notification connection is a process-wide singleton whose `errors` flow
 * replays its last value. When the access token expired it emitted an
 * AuthenticationFailed that was retained in the replay cache. After re-login a
 * brand-new NotificationViewModel was created, subscribed to `errors`, and
 * immediately re-received that stale failure — flipping `authError` to true and
 * driving MainScreen straight back to the login screen.
 *
 * A freshly constructed ViewModel must clear the connection's retained error
 * before subscribing, so a previous session's failure cannot leak into it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelAuthErrorTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun freshViewModel_doesNotInheritStaleAuthError() =
        runTest(testDispatcher) {
            // A previous session left an AuthenticationFailed in the connection's replay cache.
            val connection =
                FakeNotificationConnection(
                    seedError =
                        NotificationConnection.NotificationConnectionError
                            .AuthenticationFailed(
                                NotificationConnection.AuthFailureReason.TOKEN_RENEWAL_FAILED,
                            ),
                )

            val viewModel =
                NotificationViewModel(
                    connection,
                    LanguageManager(),
                    FakeSyncService(),
                    noOpTableStatusCacheWriter(),
                    FakeOverlayEventBus(),
                    FakeTokenStore.create(),
                )
            advanceUntilIdle()

            assertEquals(
                1,
                connection.clearErrorsCount,
                "init must clear the connection's retained error before subscribing",
            )
            assertFalse(
                viewModel.authError.value,
                "a freshly created ViewModel must not inherit a previous session's auth error",
            )
        }
}
