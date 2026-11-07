package org.example.project.core.presentation.components.notification

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.NoOpClientErrorReporter
import org.example.project.core.presentation.error.UiError
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Default implementation of [OverlayEventBus]. Pure event producer; it does not keep
 * queue state. State is rebuilt by the consuming ViewModel.
 */
class OverlayEventBusImpl(
    /**
     * Records every error the bus shows. Defaulted so tests and previews can construct the bus
     * without a server; production DI passes the real reporter.
     */
    private val clientErrorReporter: ClientErrorReporter = NoOpClientErrorReporter,
) : OverlayEventBus {
    private val _events = MutableSharedFlow<OverlayEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    override fun showSuccess(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) = emit(
        NotificationState(
            title = title,
            message = message,
            type = NotificationSeverity.SUCCESS,
            displayMode = displayMode,
            autoDismissMs = autoDismissMs,
        ),
    )

    override fun showWarning(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) = emit(
        NotificationState(
            title = title,
            message = message,
            type = NotificationSeverity.WARNING,
            displayMode = displayMode,
            autoDismissMs = autoDismissMs,
        ),
    )

    override fun showError(
        title: String,
        message: String,
        displayMode: DisplayMode,
        retryLabel: String?,
        retryAction: (() -> Unit)?,
        id: String?,
    ) = emit(
        NotificationState(
            title = title,
            message = message,
            type = NotificationSeverity.ERROR,
            displayMode = displayMode,
            autoDismissMs = displayMode.errorAutoDismissMs(),
            actions =
                if (retryLabel != null && retryAction != null) {
                    listOf(NotificationAction(retryLabel, retryAction, isPrimary = true))
                } else {
                    emptyList()
                },
        ).let { state -> if (id != null) state.copy(id = id) else state },
    )

    override fun showError(
        error: UiError,
        title: String,
        message: String,
        screen: String?,
        displayMode: DisplayMode?,
        retryLabel: String?,
        retryAction: (() -> Unit)?,
    ) {
        // Report first: showing is best-effort UI, recording is the part that must not be skipped
        // if anything below throws.
        clientErrorReporter.report(error = error, shownTitle = title, shownMessage = message, screen = screen)
        val resolvedMode = displayMode ?: error.defaultDisplayMode()
        emit(
            NotificationState(
                title = title,
                message = message,
                type = NotificationSeverity.ERROR,
                displayMode = resolvedMode,
                autoDismissMs = resolvedMode.errorAutoDismissMs(),
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

    override fun showInfo(
        title: String,
        message: String,
        displayMode: DisplayMode,
        autoDismissMs: Long?,
    ) = emit(
        NotificationState(
            title = title,
            message = message,
            type = NotificationSeverity.SUCCESS, // existing behavior: info uses success styling
            displayMode = displayMode,
            autoDismissMs = autoDismissMs,
        ),
    )

    @OptIn(ExperimentalUuidApi::class)
    override fun showLoading(
        title: String,
        message: String,
        dismissEnable: Boolean,
    ): String {
        val id = Uuid.random().toString()
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
        _events.tryEmit(OverlayEvent.DismissCurrent)
    }

    override fun dismissById(id: String) {
        _events.tryEmit(OverlayEvent.DismissById(id))
    }

    override fun clearAll() {
        _events.tryEmit(OverlayEvent.ClearAll)
    }

    private fun emit(state: NotificationState) {
        _events.tryEmit(OverlayEvent.Enqueue(state))
    }
}

/**
 * Dialog for errors a toast structurally cannot convey, toast for the rest.
 *
 * Per-field validation errors flatten to a single unreadable line in a toast, and a 5xx is where
 * the traceId matters because the user has to read it out to support. Everything else — auth and
 * business 4xx, transport blips — is routine and user-correctable, and a modal for each one trains
 * staff to dismiss reflexively, which is how a real error gets clicked away during service.
 *
 * Deliberately does NOT test traceId: the server stamps one on every ProblemDetail, so keying on it
 * would escalate every server error to a modal.
 */
private fun UiError.defaultDisplayMode(): DisplayMode =
    if (this is UiError.FromServer && (fieldErrors.isNotEmpty() || httpStatus >= 500)) {
        DisplayMode.DIALOG
    } else {
        DisplayMode.TOAST
    }

/** Error toasts close themselves; error dialogs wait for the operator. */
private fun DisplayMode.errorAutoDismissMs(): Long? = if (this == DisplayMode.TOAST) OverlayEventBus.ERROR_TOAST_AUTO_DISMISS_MS else null
