package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.browser.ServerBuildWatcher
import org.example.project.core.domain.dto.websocket.NotificationDto
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The server's hello frame reaches the update banner's [ServerBuildWatcher]. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationViewModelServerHelloTest {
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

    private fun hello(version: String) = NotificationDto.ServerHelloDto(timestamp = "2026-10-07T10:00:00Z", serverVersion = version)

    private fun viewModel(
        connection: FakeNotificationConnection,
        watcher: ServerBuildWatcher,
    ) = NotificationViewModel(
        connection,
        LanguageManager(),
        FakeSyncService(),
        noOpTableStatusCacheWriter(),
        FakeOverlayEventBus(),
        FakeTokenStore.create(),
        watcher,
    )

    @Test
    fun aHelloWithADifferentVersion_flagsAnUpdate() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val watcher = ServerBuildWatcher(bundleVersion = "1.1.0")
            viewModel(connection, watcher)
            advanceUntilIdle()

            connection.emitMessage(hello("1.1.1"))
            advanceUntilIdle()

            assertTrue(watcher.updateAvailable.value)
        }

    @Test
    fun aHelloWithTheSameVersion_doesNotFlagAnUpdate() =
        runTest(testDispatcher) {
            val connection = FakeNotificationConnection()
            val watcher = ServerBuildWatcher(bundleVersion = "1.1.0")
            viewModel(connection, watcher)
            advanceUntilIdle()

            connection.emitMessage(hello("1.1.0"))
            advanceUntilIdle()

            assertFalse(watcher.updateAvailable.value)
        }
}
