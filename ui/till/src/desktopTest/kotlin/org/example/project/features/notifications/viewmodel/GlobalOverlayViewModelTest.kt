package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.NotificationSeverity
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [GlobalOverlayViewModel]: it consumes [OverlayEventBus] events and exposes
 * a one-at-a-time notification queue via [currentNotification].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalOverlayViewModelTest {
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
    fun showSuccess_emitsCurrentNotification() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showSuccess(title = "Saved", message = "Record saved")
            advanceUntilIdle()

            val current = viewModel.currentNotification.value
            assertEquals("Saved", current?.title)
            assertEquals("Record saved", current?.message)
            assertEquals(NotificationSeverity.SUCCESS, current?.type)
        }

    @Test
    fun dismissCurrent_advancesQueue() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showSuccess(title = "First", message = "msg1")
            viewModel.showWarning(title = "Second", message = "msg2")
            advanceUntilIdle()

            assertEquals("First", viewModel.currentNotification.value?.title)

            viewModel.dismissCurrent()
            advanceUntilIdle()

            assertEquals("Second", viewModel.currentNotification.value?.title)

            viewModel.dismissCurrent()
            advanceUntilIdle()

            assertNull(viewModel.currentNotification.value)
        }

    @Test
    fun dismissById_removesMatchingNotification() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            val loadingId = viewModel.showLoading(title = "Loading", message = "please wait")
            viewModel.showSuccess(title = "Done", message = "completed")
            advanceUntilIdle()

            assertEquals("Loading", viewModel.currentNotification.value?.title)

            viewModel.dismissById(loadingId)
            advanceUntilIdle()

            assertEquals("Done", viewModel.currentNotification.value?.title)
        }

    @Test
    fun clearAll_clearsQueue() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showSuccess(title = "A", message = "a")
            viewModel.showWarning(title = "B", message = "b")
            advanceUntilIdle()

            viewModel.clearAll()
            advanceUntilIdle()

            assertNull(viewModel.currentNotification.value)
        }

    @Test
    fun clearAll_dropsALoadingOverlayBehindAnErrorDialog() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showError(title = "Print", message = "disabled", displayMode = DisplayMode.DIALOG)
            val loadingId = viewModel.showLoading(title = "Sync", message = "wait")
            advanceUntilIdle()

            viewModel.clearAll()
            advanceUntilIdle()
            assertNull(viewModel.currentNotification.value)

            // The loading id outliving the clear must be harmless to dismiss afterwards.
            viewModel.dismissById(loadingId)
            advanceUntilIdle()
            assertNull(viewModel.currentNotification.value)
        }

    @Test
    fun duplicateNotificationsAreDeDuplicated() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showSuccess(title = "Duplicate", message = "same")
            viewModel.showSuccess(title = "Duplicate", message = "same")
            viewModel.showWarning(title = "Duplicate", message = "same") // same title/message but different type -> not duplicate
            advanceUntilIdle()

            // The duplicate success must be skipped in the VM queue, leaving success then warning.
            assertEquals("Duplicate", viewModel.currentNotification.value?.title)
            assertEquals(NotificationSeverity.SUCCESS, viewModel.currentNotification.value?.type)

            viewModel.dismissCurrent()
            advanceUntilIdle()

            assertEquals(NotificationSeverity.WARNING, viewModel.currentNotification.value?.type)

            viewModel.dismissCurrent()
            advanceUntilIdle()

            assertNull(viewModel.currentNotification.value)
        }

    @Test
    fun dismissById_withdrawsAnErrorEnqueuedUnderACallerId() =
        runTest(testDispatcher) {
            val bus = FakeOverlayEventBus()
            val viewModel = GlobalOverlayViewModel(bus)
            advanceUntilIdle()

            viewModel.showError(title = "Offline", message = "Stream lost", id = "connection-error")
            viewModel.showSuccess(title = "Saved", message = "ok")
            advanceUntilIdle()
            assertEquals("Offline", viewModel.currentNotification.value?.title)

            viewModel.dismissById("connection-error")
            advanceUntilIdle()

            assertEquals("Saved", viewModel.currentNotification.value?.title)
        }
}
