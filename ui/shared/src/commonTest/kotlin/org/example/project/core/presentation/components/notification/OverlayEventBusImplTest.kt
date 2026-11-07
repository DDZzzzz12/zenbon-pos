package org.example.project.core.presentation.components.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayEventBusImplTest {
    @Test
    fun showSuccess_emitsEnqueueEventWithSuccessState() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.showSuccess(title = "Saved", message = "Changes saved")
            advanceUntilIdle()
            collectJob.cancel()

            val enqueued = events.filterIsInstance<OverlayEvent.Enqueue>()
            assertEquals(1, enqueued.size)
            assertEquals("Saved", enqueued.first().state.title)
            assertEquals(NotificationSeverity.SUCCESS, enqueued.first().state.type)
            assertEquals(DisplayMode.TOAST, enqueued.first().state.displayMode)
        }

    @Test
    fun showLoading_emitsEnqueueEventAndReturnsId() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            val id = bus.showLoading(title = "Working", message = "Please wait")
            advanceUntilIdle()
            collectJob.cancel()

            assertNotNull(id)
            val enqueued = events.filterIsInstance<OverlayEvent.Enqueue>()
            assertEquals(1, enqueued.size)
            assertEquals(id, enqueued.first().state.id)
            assertEquals(NotificationSeverity.LOADING, enqueued.first().state.type)
            assertEquals(DisplayMode.DIALOG, enqueued.first().state.displayMode)
        }

    @Test
    fun dismissById_emitsDismissByIdEvent() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.dismissById("id-1")
            advanceUntilIdle()
            collectJob.cancel()

            val dismissed = events.filterIsInstance<OverlayEvent.DismissById>()
            assertEquals(1, dismissed.size)
            assertEquals("id-1", dismissed.first().id)
        }

    @Test
    fun dismissCurrent_emitsDismissCurrentEvent() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.dismissCurrent()
            advanceUntilIdle()
            collectJob.cancel()

            assertTrue(events.any { it is OverlayEvent.DismissCurrent })
        }

    @Test
    fun clearAll_emitsClearAllEvent() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.clearAll()
            advanceUntilIdle()
            collectJob.cancel()

            assertTrue(events.any { it is OverlayEvent.ClearAll })
        }

    @Test
    fun showError_withRetryAction_emitsStateWithAction() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.showError(
                title = "Failed",
                message = "Try again",
                retryLabel = "Retry",
                retryAction = {},
            )
            advanceUntilIdle()
            collectJob.cancel()

            val enqueued = events.filterIsInstance<OverlayEvent.Enqueue>()
            assertEquals(1, enqueued.size)
            assertEquals(NotificationSeverity.ERROR, enqueued.first().state.type)
            assertEquals(
                1,
                enqueued
                    .first()
                    .state.actions.size,
            )
            assertEquals(
                "Retry",
                enqueued
                    .first()
                    .state.actions
                    .first()
                    .label,
            )
        }

    @Test
    fun showError_toast_closesItselfAfterEightSeconds() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.showError(title = "Failed", message = "Try again")
            advanceUntilIdle()
            collectJob.cancel()

            val state = events.filterIsInstance<OverlayEvent.Enqueue>().single().state
            assertEquals(8_000L, OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS)
            assertEquals(OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS, state.autoDismissMs)
        }

    @Test
    fun showError_dialog_staysUntilDismissed() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.showError(title = "Failed", message = "Read this", displayMode = DisplayMode.DIALOG)
            advanceUntilIdle()
            collectJob.cancel()

            assertNull(
                events
                    .filterIsInstance<OverlayEvent.Enqueue>()
                    .single()
                    .state.autoDismissMs,
            )
        }

    @Test
    fun showError_withCallerId_isEnqueuedUnderThatId() =
        runTest(StandardTestDispatcher()) {
            val bus = OverlayEventBusImpl()
            val events = mutableListOf<OverlayEvent>()
            val collectJob = launch { bus.events.collect { events.add(it) } }
            advanceUntilIdle()

            bus.showError(title = "Offline", message = "Stream lost", id = "connection-error")
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "connection-error",
                events
                    .filterIsInstance<OverlayEvent.Enqueue>()
                    .single()
                    .state.id,
            )
        }
}
