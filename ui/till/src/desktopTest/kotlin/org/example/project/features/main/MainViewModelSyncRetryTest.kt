package org.example.project.features.main

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.main.presentation.viewmodels.MainViewModel
import org.example.project.testutils.fakes.FakeSyncService
import org.example.project.testutils.fakes.FakeTerminalRegistrationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2E-2026-09-19: restarting pos-server left the till on the "Daten werden synchronisiert" overlay
 * until the app was force-stopped — `performSync` ran once, and the overlay is shown for as long as
 * `syncCompleted` is false. The sync must therefore keep retrying until it succeeds.
 *
 * The retry ladder never ends while the server stays down, so these tests step the clock with
 * `runCurrent()` + `advanceTimeBy()`: `advanceUntilIdle()` drains delayed tasks as well and would
 * spin the loop forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSyncRetryTest {
    private val testDispatcher = StandardTestDispatcher()
    private val retryDelays = listOf(2_000L, 5_000L, 10_000L)

    /** Fails [failures] times, then succeeds; counts every attempt. */
    private class FlakyPreferenceSync(
        var failures: Int,
    ) : PreferenceSyncService {
        var attempts = 0

        override suspend fun syncPreferences() {
            attempts++
            if (failures-- > 0) throw NetworkApiException(RuntimeException("connection refused"))
        }
    }

    private fun viewModel(preferences: PreferenceSyncService) =
        MainViewModel(
            preferenceSyncService = preferences,
            syncService = FakeSyncService(),
            terminalRegistrationService = FakeTerminalRegistrationService(),
            retryDelaysMs = retryDelays,
        )

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a sync that fails while the server is down completes once it is back`() =
        runTest(testDispatcher) {
            val preferences = FlakyPreferenceSync(failures = 2)
            val viewModel = viewModel(preferences)

            viewModel.performSync()
            runCurrent()
            assertFalse(viewModel.uiState.value.syncCompleted, "the first attempt fails while the server is down")
            assertNotNull(viewModel.uiState.value.error, "and the operator is told why")
            assertEquals(1, preferences.attempts)

            // The first two gaps of the backoff; the third attempt is the one that gets through.
            advanceTimeBy(retryDelays[0] + retryDelays[1] + 1)
            runCurrent()

            assertEquals(3, preferences.attempts, "it retried on the backoff, not in a tight loop")
            assertTrue(viewModel.uiState.value.syncCompleted, "the till syncs itself once the server answers")
            assertNull(viewModel.uiState.value.error, "and the failure message is cleared")
        }

    @Test
    fun `a successful first sync does not retry`() =
        runTest(testDispatcher) {
            val preferences = FlakyPreferenceSync(failures = 0)
            val viewModel = viewModel(preferences)

            viewModel.performSync()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, preferences.attempts)
            assertTrue(viewModel.uiState.value.syncCompleted)
        }

    @Test
    fun `logging out stops the retries`() =
        runTest(testDispatcher) {
            val preferences = FlakyPreferenceSync(failures = Int.MAX_VALUE)
            val viewModel = viewModel(preferences)

            viewModel.performSync()
            runCurrent()
            viewModel.onLogout()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, preferences.attempts, "a signed-out till must not keep calling the server")
        }

    @Test
    fun `logging out clears the sync error and re-arms the overlay for the next sign-in`() =
        runTest(testDispatcher) {
            val preferences = FlakyPreferenceSync(failures = Int.MAX_VALUE)
            val viewModel = viewModel(preferences)

            viewModel.performSync()
            runCurrent()
            assertNotNull(viewModel.uiState.value.error)

            viewModel.onLogout()

            assertNull(viewModel.uiState.value.error, "a previous session's sync error must not greet the next one")
            assertFalse(viewModel.uiState.value.syncCompleted)
            assertFalse(viewModel.uiState.value.isLoading)
        }
}
