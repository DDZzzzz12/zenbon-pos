package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.network.NotificationConnection
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
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

/**
 * A "connection to the server lost" toast must not outlive the outage it reports: once the stream
 * is back it reads as a live fault. A repeated failure replaces the toast rather than stacking a
 * second copy behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelConnectionErrorDismissTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun streamLost() =
        NotificationConnection.NotificationConnectionError.ConnectionFailed(
            NotificationConnection.ConnectionFailureReason.STREAM_LOST,
        )

    private fun viewModel(
        connection: FakeNotificationConnection,
        bus: FakeOverlayEventBus,
    ) = NotificationViewModel(
        connection,
        LanguageManager(),
        FakeSyncService(),
        noOpTableStatusCacheWriter(),
        bus,
        FakeTokenStore.create(),
    )

    @Test
    fun theConnectionErrorToastIsWithdrawnWhenTheStreamReconnects() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitError(streamLost())
            advanceUntilIdle()
            val shown =
                bus.history
                    .filterIsInstance<OverlayEvent.Enqueue>()
                    .single()
                    .state
            assertEquals(NotificationViewModel.CONNECTION_ERROR_OVERLAY_ID, shown.id)

            connection.emitConnectionState(NotificationConnection.ConnectionState.Connected)
            advanceUntilIdle()

            assertEquals(
                OverlayEvent.DismissById(NotificationViewModel.CONNECTION_ERROR_OVERLAY_ID),
                bus.history.last(),
                "a reconnect must withdraw the connection error by its id",
            )
        }

    @Test
    fun aRepeatedConnectionFailureReplacesTheToastInsteadOfStackingIt() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val bus = FakeOverlayEventBus()
            viewModel(connection, bus)
            advanceUntilIdle()

            connection.emitError(streamLost())
            advanceUntilIdle()
            connection.emitError(
                NotificationConnection.NotificationConnectionError.ConnectionFailed(
                    NotificationConnection.ConnectionFailureReason.RETRIES_EXHAUSTED,
                ),
            )
            advanceUntilIdle()

            val history = bus.history
            val lastEnqueue = history.indexOfLast { it is OverlayEvent.Enqueue }
            assertEquals(
                OverlayEvent.DismissById(NotificationViewModel.CONNECTION_ERROR_OVERLAY_ID),
                history[lastEnqueue - 1],
                "the previous connection error must be withdrawn before the new one is shown",
            )
        }
}
