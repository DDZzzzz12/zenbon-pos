package org.example.project.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.example.project.core.auth.SessionStatus
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.LocalScreenName
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.SessionState
import org.koin.compose.koinInject

/**
 * Observes a [UiError] from a ViewModel and surfaces it, picking the surface by what the
 * error actually carries rather than by which call site raised it.
 *
 * - **Detail-bearing errors escalate** to [org.example.project.core.presentation.error.PosErrorDialog]:
 *   per-field validation errors (a toast can only show one flattened line) and server faults
 *   (5xx), where the dialog's `traceId` plus copy button is what lets a user read an
 *   identifier out to support. A modal is also unmissable, which matters when the failure is
 *   fiscal.
 * - **Everything else toasts** on the global notification queue — auth and business 4xx,
 *   transport blips, unknowns. These are routine and user-correctable, and a modal for each
 *   one trains staff to dismiss reflexively, which is how a real error gets clicked away
 *   during service.
 *
 * [title] is the toast heading only; the dialog resolves its own from [UiError.titleKey].
 *
 * Usage:
 * ```kotlin
 * PosErrorWatcher(
 *     error = state.error,
 *     title = str(SomeKeys.title),
 *     onClear = { viewModel.clearError() },
 * )
 * ```
 *
 * Note both watchers are mounted unconditionally, with the inactive one fed `null`. Gating
 * one out behind an `if` would tear down the dialog's queue the instant [onClear] nulls the
 * VM's error, and the dialog would never render.
 *
 * @param suppressWhenSignedOut drop errors while the session is `UNAUTHENTICATED`. The default is
 *   right for screens that live *behind* the login gate: when a refresh fails, the app root is
 *   already switching away, and a toast from the dying screen would flash for no reason. Pass
 *   `false` on a screen that renders *as* the unauthenticated state — the setup/bootstrap wizard —
 *   where `UNAUTHENTICATED` is the normal condition rather than a sign-out, and suppressing would
 *   swallow every error the operator needs to see.
 */
@Composable
fun PosErrorWatcher(
    error: UiError?,
    title: String,
    onClear: () -> Unit,
    displayMode: DisplayMode? = null,
    retryAction: (() -> Unit)? = null,
    suppressWhenSignedOut: Boolean = true,
    /**
     * Overrides the ambient [LocalScreenName] for a watcher mounted outside a navigation graph —
     * the app-root ones that render by a destination switch rather than a NavHost. Screens inside
     * a graph should leave this null and let the provider supply it.
     */
    screen: String? = null,
) {
    val sessionState = koinInject<SessionState>()
    val resolvedScreen = screen ?: LocalScreenName.current
    val reporter = koinInject<ClientErrorReporter>()
    val overlay = LocalGlobalOverlayViewModel.current

    // displayMessage(), not str(messageKey): most PosErrorCodes have no dedicated translation, so
    // the raw key resolves to a generic "something went wrong" and discards the server's own
    // detail — which is often the only thing telling the operator what to fix.
    val message = error?.displayMessage()
    val retryLabel = if (retryAction != null) str(CommonKeys.retry) else null

    // One path for every error: the bus decides toast vs dialog and records it. Escalation used to
    // be decided here and the dialog hosted here, which is why only screens mounting this composable
    // could show error detail or be reported at all.
    LaunchedEffect(error, message) {
        if (error == null || message == null) return@LaunchedEffect
        val signedOut = suppressWhenSignedOut && sessionState.status == SessionStatus.UNAUTHENTICATED
        if (signedOut) {
            // Nothing is shown, so the bus never sees it — report directly, because a failed
            // refresh is exactly the failure an operator cannot describe afterwards.
            reporter.report(error = error, shownTitle = title, shownMessage = message, screen = resolvedScreen)
        } else {
            overlay.showError(
                error = error,
                title = title,
                message = message,
                screen = resolvedScreen,
                displayMode = displayMode,
                retryLabel = retryLabel,
                retryAction = retryAction,
            )
        }
        onClear()
    }
}
