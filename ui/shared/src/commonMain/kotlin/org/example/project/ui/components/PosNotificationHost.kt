package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.NotificationSeverity
import org.example.project.core.presentation.components.notification.NotificationState
import org.example.project.core.presentation.error.PosErrorDialog
import kotlin.time.Clock

/**
 * Renders the current global notification from a [NotificationState] queue entry.
 * ViewModel-free: state in, [onDismiss] lambda out. Routes by [NotificationState.displayMode]:
 * DIALOG + LOADING -> [PosLoadingOverlay]; DIALOG + other -> [PosNotificationPopup];
 * TOAST -> bottom-aligned [PosToast]. Retry/action comes from [NotificationState.actions].
 */
@Composable
fun PosNotificationHost(
    notification: NotificationState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    notification?.let {
        // Keyed by id AND content: consecutive notifications share this call site, so without a key
        // the next toast inherited the closed one's `visible = false` (and its finished auto-dismiss
        // timer) and sat invisible at the head of the queue, holding back everything behind it. id
        // alone is not enough — a producer that reuses an id (e.g. repeated connection-error toasts
        // with a different message) can leave two adjacent queue entries sharing one id, and keying
        // on id alone would still carry the first's finished state into the second.
        key(it.id, it.title, it.message, it.displayMode) {
            when (it.displayMode) {
                DisplayMode.DIALOG -> {
                    val typedError = it.error
                    if (it.type == NotificationSeverity.LOADING) {
                        PosLoadingOverlay(
                            title = it.title,
                            message = it.message,
                        )
                    } else if (typedError != null) {
                        // A typed error escalated to a dialog: render the detail view rather than the
                        // plain popup, so traceId, error code and field errors are on screen and
                        // copyable. This is what makes that detail available everywhere instead of
                        // only on screens that mount their own watcher.
                        PosErrorDialog(
                            error = typedError,
                            onDismiss = { if (it.dismissEnable) onDismiss() },
                            timestampLabel = formatOverlayTimestamp(),
                            retry =
                                it.actions.firstOrNull()?.let { action ->
                                    {
                                        action.action()
                                        onDismiss()
                                    }
                                },
                        )
                    } else {
                        PosNotificationPopup(
                            title = it.title,
                            message = it.message,
                            severity =
                                when (it.type) {
                                    NotificationSeverity.SUCCESS -> PosNotificationSeverity.SUCCESS
                                    NotificationSeverity.WARNING -> PosNotificationSeverity.WARNING
                                    NotificationSeverity.ERROR -> PosNotificationSeverity.ERROR
                                },
                            onDismiss = {
                                if (it.dismissEnable) onDismiss()
                            },
                            onRetry =
                                it.actions.firstOrNull()?.let { action ->
                                    {
                                        action.action()
                                        onDismiss()
                                    }
                                },
                            dismissEnabled = it.dismissEnable,
                        )
                    }
                }

                DisplayMode.TOAST -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = 24.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        PosToast(
                            title = it.title,
                            message = it.message,
                            type =
                                when (it.type) {
                                    NotificationSeverity.SUCCESS -> PosToastType.SUCCESS
                                    NotificationSeverity.WARNING -> PosToastType.WARNING
                                    NotificationSeverity.ERROR -> PosToastType.ERROR
                                    else -> PosToastType.SUCCESS
                                },
                            onDismiss = { onDismiss() },
                            autoDismissMs = it.autoDismissMs,
                            actionLabel = it.actions.firstOrNull()?.label,
                            onAction = it.actions.firstOrNull()?.let { action -> { action.action() } },
                        )
                    }
                }
            }
        }
    }
}

/** Wall-clock stamp shown on the detail dialog, so a user can quote when the error happened. */
private fun formatOverlayTimestamp(): String {
    val now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.hour.toString().padStart(2, '0')}:" +
        "${now.minute.toString().padStart(2, '0')}:" +
        now.second.toString().padStart(2, '0')
}
