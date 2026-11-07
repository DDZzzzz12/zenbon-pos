package org.example.project.core.presentation.components.notification

import kotlinx.coroutines.flow.Flow
import org.example.project.core.presentation.error.UiError

/**
 * Fire-and-forget event sink for global success / warning / error / loading overlays.
 *
 * This is the shared contract consumed by both composeApp and adminApp. Each app provides
 * its own consumer (a ViewModel or equivalent) that reads [events], builds a queue of
 * [NotificationState], and exposes it to [PosNotificationHost].
 */
interface OverlayEventBus {
    /** Observable stream of overlay events emitted by screens and ViewModels. */
    val events: Flow<OverlayEvent>

    /** Enqueue a success toast/dialog. */
    fun showSuccess(
        title: String,
        message: String,
        displayMode: DisplayMode = DisplayMode.TOAST,
        autoDismissMs: Long? = 3000L,
    )

    /** Enqueue a warning toast/dialog. */
    fun showWarning(
        title: String,
        message: String,
        displayMode: DisplayMode = DisplayMode.TOAST,
        autoDismissMs: Long? = 5000L,
    )

    /**
     * Enqueue an error toast/dialog with optional retry action.
     *
     * A toast closes itself after [ERROR_TOAST_AUTO_DISMISS_MS]; a dialog stays until dismissed.
     *
     * @param retryLabel Localized label for the retry button. Required when [retryAction]
     *   is non-null; omitted when there is no retry.
     * @param id Caller-owned ID, for a producer that must later withdraw this error with
     *   [dismissById] — a connection error once the connection is back, say. Null draws a random one.
     *   Note: a consumer may skip enqueuing this notification if its content (title/message/etc.,
     *   ignoring id) already matches one sitting in the queue — [GlobalOverlayViewModel] does — so
     *   a later [dismissById] with this [id] can find nothing to withdraw.
     */
    fun showError(
        title: String,
        message: String,
        displayMode: DisplayMode = DisplayMode.TOAST,
        retryLabel: String? = null,
        retryAction: (() -> Unit)? = null,
        id: String? = null,
    )

    /**
     * Enqueue a typed error, and report it.
     *
     * Prefer this over the string overload above wherever a [UiError] is in hand. The bus — not the
     * caller — decides whether the error is unmissable enough for a dialog or routine enough for a
     * toast, and reports it to the client-error log. Centralising both means an error cannot be
     * shown without being recorded, and cannot be missed because a screen forgot to observe it.
     *
     * [title] and [message] arrive already localized: resolving a [org.example.project.core.presentation.strings.StringKey]
     * needs either a composition or a language, and the bus has neither.
     *
     * A toast closes itself after [ERROR_TOAST_AUTO_DISMISS_MS]; a dialog stays until dismissed.
     *
     * @param screen route or screen the error surfaced on, recorded in the log line.
     * @param displayMode overrides the bus's choice. Null means "apply the policy"; pass one only
     *   where the caller genuinely knows better — a fiscal flow that must not be dismissible as a
     *   toast, for instance.
     */
    fun showError(
        error: UiError,
        title: String,
        message: String,
        screen: String? = null,
        displayMode: DisplayMode? = null,
        retryLabel: String? = null,
        retryAction: (() -> Unit)? = null,
    )

    /** Enqueue an info toast/dialog (uses success styling). */
    fun showInfo(
        title: String,
        message: String,
        displayMode: DisplayMode = DisplayMode.TOAST,
        autoDismissMs: Long? = 3000L,
    )

    /** Enqueue a blocking loading dialog. Returns a caller-owned ID for later dismissal. */
    fun showLoading(
        title: String,
        message: String,
        dismissEnable: Boolean = false,
    ): String

    /** Dismiss the front notification and advance the queue. */
    fun dismissCurrent()

    /** Dismiss a specific notification by the ID returned from [showLoading]. */
    fun dismissById(id: String)

    /** Clear every enqueued overlay. */
    fun clearAll()

    companion object {
        /**
         * How long an error toast stays up. Longer than a warning (5 s) because an error is read,
         * not glanced at, but finite: the queue shows one notification at a time, so an error
         * toast without a timeout held back everything behind it until someone tapped its close
         * button. Error dialogs (validation detail, 5xx) are unaffected and still need a tap.
         */
        const val ERROR_TOAST_AUTO_DISMISS_MS = 8_000L
    }
}

/** Events emitted by [OverlayEventBus] and consumed by the per-app overlay consumer. */
sealed interface OverlayEvent {
    data class Enqueue(
        val state: NotificationState,
    ) : OverlayEvent

    data class DismissById(
        val id: String,
    ) : OverlayEvent

    data object DismissCurrent : OverlayEvent

    data object ClearAll : OverlayEvent
}
