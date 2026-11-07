package org.example.project.testutils.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.NotificationAction
import org.example.project.core.presentation.components.notification.NotificationSeverity
import org.example.project.core.presentation.components.notification.NotificationState
import org.example.project.core.presentation.components.notification.OverlayEvent
import org.example.project.core.presentation.components.notification.OverlayEventBus
import org.example.project.core.presentation.error.UiError

/**
 * Test double for [OverlayEventBus] that records every enqueued/dismiss/clear event
 * so tests can assert on the events a producer emitted.
 */
class FakeOverlayEventBus : OverlayEventBus {
    private val _events = MutableSharedFlow<OverlayEvent>(extraBufferCapacity = 64)
    override val events: Flow<OverlayEvent> = _events

    private val _history = mutableListOf<OverlayEvent>()
    val history: List<OverlayEvent> get() = _history.toList()

    override fun showSuccess(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) {
        emit(
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.SUCCESS,
                displayMode = displayMode,
                autoDismissMs = autoDismissMs,
            ),
        )
    }

    override fun showError(
        error: UiError,
        title: String,
        message: String,
        screen: String?,
        displayMode: DisplayMode?,
        retryLabel: String?,
        retryAction: (() -> Unit)?,
    ) {
        val resolvedMode =
            displayMode ?: if (error is UiError.FromServer && (error.fieldErrors.isNotEmpty() || error.httpStatus >= 500)) {
                DisplayMode.DIALOG
            } else {
                DisplayMode.TOAST
            }
        emit(
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.ERROR,
                displayMode = resolvedMode,
                autoDismissMs = if (resolvedMode == DisplayMode.TOAST) OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS else null,
                error = error,
                actions =
                    if (retryLabel != null && retryAction != null) {
                        listOf(NotificationAction(retryLabel, retryAction, isPrimary = true))
                    } else {
                        emptyList()
                    },
            ),
        )
    }

    override fun showWarning(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) {
        emit(
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.WARNING,
                displayMode = displayMode,
                autoDismissMs = autoDismissMs,
            ),
        )
    }

    override fun showError(
        title: String,
        message: String,
        displayMode: DisplayMode,
        retryLabel: String?,
        retryAction: (() -> Unit)?,
        id: String?,
    ) {
        val actions =
            if (retryAction != null && retryLabel != null) {
                listOf(NotificationAction(retryLabel, retryAction, isPrimary = true))
            } else {
                emptyList()
            }
        val state =
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.ERROR,
                displayMode = displayMode,
                autoDismissMs = if (displayMode == DisplayMode.TOAST) OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS else null,
                actions = actions,
            )
        emit(if (id != null) state.copy(id = id) else state)
    }

    override fun showInfo(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) {
        emit(
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.SUCCESS, // info uses success styling
                displayMode = displayMode,
                autoDismissMs = autoDismissMs,
            ),
        )
    }

    private var loadingCounter = 0

    override fun showLoading(
        title: String,
        message: String,
        dismissEnable: Boolean,
    ): String {
        val id = "loading-${++loadingCounter}"
        emit(
            NotificationState(
                id = id,
                title = title,
                message = message,
                type = NotificationSeverity.LOADING,
                displayMode = DisplayMode.DIALOG,
                autoDismissMs = null,
                dismissEnable = dismissEnable,
            ),
        )
        return id
    }

    override fun dismissCurrent() {
        emit(OverlayEvent.DismissCurrent)
    }

    override fun dismissById(id: String) {
        emit(OverlayEvent.DismissById(id))
    }

    override fun clearAll() {
        emit(OverlayEvent.ClearAll)
    }

    private fun emit(state: NotificationState) {
        val event = OverlayEvent.Enqueue(state)
        _history += event
        _events.tryEmit(event)
    }

    private fun emit(event: OverlayEvent) {
        _history += event
        _events.tryEmit(event)
    }
}
