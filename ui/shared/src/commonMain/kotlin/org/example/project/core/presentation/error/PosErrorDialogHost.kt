package org.example.project.core.presentation.error

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Hard cap on queued unhandled errors to prevent runaway storms. */
private const val MAX_QUEUE_SIZE = 5

/**
 * Typed-error dialog host. Observes a [UiError] in ViewModel state, queues
 * unhandled errors (cap = 5) and shows them one at a time via [PosErrorDialog].
 *
 * When [error] flips from null -> UiError, the new error is enqueued and
 * [onClear] is invoked so the VM resets its state — [error] itself is NOT a
 * reliable "is a dialog currently showing" signal, since it is nulled out in
 * the very same effect that enqueues the dialog. The dialog displays the
 * head of the queue; dismissing pops to the next.
 */
@Composable
fun PosErrorDialogHost(
    error: UiError?,
    onClear: () -> Unit,
) {
    val queue = remember { mutableStateListOf<QueuedError>() }
    var lastSeen by remember { mutableStateOf<UiError?>(null) }

    LaunchedEffect(error) {
        if (error != null && error !== lastSeen) {
            if (queue.size < MAX_QUEUE_SIZE) {
                queue.add(QueuedError(error, formatNow()))
            }
            lastSeen = error
            onClear()
        }
    }

    queue.firstOrNull()?.let {
        val retry = (it.error as? UiError.Network)?.retry
        PosErrorDialog(
            error = it.error,
            onDismiss = { if (queue.isNotEmpty()) queue.removeAt(0) },
            timestampLabel = it.timestamp,
            retry = retry,
        )
    }
}

private data class QueuedError(
    val error: UiError,
    val timestamp: String,
)

private fun formatNow(): String {
    val now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.hour.toString().padStart(2, '0')}:" +
        "${now.minute.toString().padStart(2, '0')}:" +
        now.second.toString().padStart(2, '0')
}
