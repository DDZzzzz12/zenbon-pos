package org.example.project.features.notifications.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.components.notification.OverlayEventBus
import org.example.project.core.presentation.components.notification.OverlayEventBusImpl
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.testutils.fakes.FakeOverlayEventBus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The overlay bus owns error display policy and reporting.
 *
 * Both used to sit in `PosErrorWatcher`, which meant a screen that did not mount one neither
 * showed nor reported its errors — the coverage gap and the reporting gap were the same gap.
 * Centralising here is what makes it impossible to show an error without recording it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayEventBusTypedErrorTest {
    private class RecordingReporter : ClientErrorReporter {
        val reported = mutableListOf<Triple<UiError, String, String>>()

        override fun report(
            error: UiError,
            shownTitle: String,
            shownMessage: String,
            screen: String?,
        ) {
            reported += Triple(error, shownTitle, shownMessage)
        }
    }

    private fun serverError(status: Int) =
        UiError.FromServer(
            code = PosErrorCode.VALIDATION_FAILED,
            traceId = "trace-1",
            titleKey = CommonKeys.error,
            messageKey = CommonKeys.error,
            httpStatus = status,
        )

    @Test
    fun serverFault_escalatesToDialogCarryingTheError() =
        runTest {
            val reporter = RecordingReporter()
            val bus = OverlayEventBusImpl(reporter)
            val error = serverError(status = 500)

            val seen = mutableListOf<OverlayEvent>()
            backgroundScope.launch { bus.events.collect { seen += it } }
            runCurrent()
            bus.showError(error = error, title = "Fehler", message = "Serverfehler")
            runCurrent()
            val event = seen.single()

            val state = (event as OverlayEvent.Enqueue).state
            assertEquals(
                DisplayMode.DIALOG,
                state.displayMode,
                "a 5xx must be unmissable — its traceId is what the user reads out to support",
            )
            assertSame(error, state.error, "the dialog can only show traceId/code if the error travels with it")
        }

    @Test
    fun routineFailure_staysAToast() =
        runTest {
            val bus = OverlayEventBusImpl(RecordingReporter())

            val seen = mutableListOf<OverlayEvent>()
            backgroundScope.launch { bus.events.collect { seen += it } }
            runCurrent()
            bus.showError(error = UiError.Network(), title = "Netzwerk", message = "Keine Verbindung")
            runCurrent()
            val event = seen.single()

            val state = (event as OverlayEvent.Enqueue).state
            assertEquals(
                DisplayMode.TOAST,
                state.displayMode,
                "routine, user-correctable failures must not train staff to dismiss modals reflexively",
            )
            assertNotNull(state.error, "even a toasted error keeps its payload for reporting")
        }

    @Test
    fun everyShownError_isReportedWithTheTextTheUserSaw() =
        runTest {
            val reporter = RecordingReporter()
            val bus = OverlayEventBusImpl(reporter)

            bus.showError(error = serverError(status = 500), title = "Fehler", message = "Serverfehler")
            bus.showError(error = UiError.Network(), title = "Netzwerk", message = "Keine Verbindung")

            assertEquals(2, reporter.reported.size, "both the dialog and the toast must be recorded")
            assertTrue(
                reporter.reported.any { it.second == "Fehler" && it.third == "Serverfehler" },
                "the log must carry the text the operator actually read, not a re-derived string",
            )
        }

    @Test
    fun callerOverride_beatsThePolicy() =
        runTest {
            val bus = OverlayEventBusImpl(RecordingReporter())

            val seen = mutableListOf<OverlayEvent>()
            backgroundScope.launch { bus.events.collect { seen += it } }
            runCurrent()
            // A fiscal flow forcing a modal on an error the policy would have toasted.
            bus.showError(
                error = UiError.Network(),
                title = "Netzwerk",
                message = "Keine Verbindung",
                displayMode = DisplayMode.DIALOG,
            )
            runCurrent()

            assertEquals(
                DisplayMode.DIALOG,
                (seen.single() as OverlayEvent.Enqueue).state.displayMode,
                "an explicit displayMode must win, or billing screens silently lose their modal",
            )
        }

    @Test
    fun nonErrorNotifications_carryNoErrorPayload() =
        runTest {
            val bus = OverlayEventBusImpl(RecordingReporter())

            val seen = mutableListOf<OverlayEvent>()
            backgroundScope.launch { bus.events.collect { seen += it } }
            runCurrent()
            bus.showInfo(title = "Gespeichert", message = "OK")
            runCurrent()
            val event = seen.single()

            assertNull(
                (event as OverlayEvent.Enqueue).state.error,
                "an info toast has no error behind it and must not render the detail dialog",
            )
        }

    @Test
    fun routineToast_closesItself_butAServerFaultDialogWaitsForTheOperator() =
        runTest {
            val bus = OverlayEventBusImpl(RecordingReporter())

            val seen = mutableListOf<OverlayEvent>()
            backgroundScope.launch { bus.events.collect { seen += it } }
            runCurrent()
            bus.showError(error = UiError.Network(), title = "Netzwerk", message = "Keine Verbindung")
            bus.showError(error = serverError(status = 500), title = "Fehler", message = "Serverfehler")
            runCurrent()

            val (toast, dialog) = seen.map { (it as OverlayEvent.Enqueue).state }
            assertEquals(
                OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS,
                toast.autoDismissMs,
                "an error toast with no timeout heads the queue and holds back every notification behind it",
            )
            assertNull(dialog.autoDismissMs, "a 5xx dialog carries a traceId the operator may still be reading out")
        }

    @Test
    fun fakeBus_callerOverrideToToast_stillCarriesTheToastAutoDismiss() {
        // FakeOverlayEventBus.showError(error, ...) resolves its own DIALOG-vs-TOAST policy from
        // the error, same as the real bus — but a caller-supplied displayMode must still win, and
        // the resulting toast must still carry the toast auto-dismiss, not a dialog's `null`.
        val bus = FakeOverlayEventBus()

        bus.showError(
            error = serverError(status = 500), // policy alone would pick DIALOG
            title = "Fehler",
            message = "Serverfehler",
            displayMode = DisplayMode.TOAST,
        )

        val state = (bus.history.single() as OverlayEvent.Enqueue).state
        assertEquals(DisplayMode.TOAST, state.displayMode, "an explicit displayMode must beat the fake's own policy")
        assertEquals(
            OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS,
            state.autoDismissMs,
            "a caller-forced toast must still auto-dismiss like every other error toast",
        )
    }
}
