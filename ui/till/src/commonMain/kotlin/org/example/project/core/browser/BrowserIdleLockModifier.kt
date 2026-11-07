package org.example.project.core.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private const val IDLE_TICK_MS = 15_000L

/**
 * A modifier that feeds pointer and key input to an [IdleLock] and ticks it every 15 s.
 *
 * Returns [Modifier] unchanged when [enabled] is false. Input is observed in the Initial pass so a
 * child that consumes the event still counts as activity; synthetic hover re-dispatches do not
 * (see [isUserActivity]).
 */
@Composable
fun rememberIdleLockModifier(
    enabled: Boolean,
    minutes: Int,
    onLock: () -> Unit,
): Modifier {
    if (!enabled) return Modifier
    val currentOnLock by rememberUpdatedState(onLock)
    val lock =
        remember(minutes) {
            val origin = TimeSource.Monotonic.markNow()
            IdleLock(minutes, clock = { origin.elapsedNow().inWholeMilliseconds }, onLock = { currentOnLock() })
        }
    LaunchedEffect(lock) {
        while (true) {
            delay(IDLE_TICK_MS)
            lock.tick()
        }
    }
    return Modifier
        .pointerInput(lock) {
            awaitPointerEventScope {
                var lastPosition: Offset? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val position = event.changes.firstOrNull()?.position
                    if (isUserActivity(event.type, position, lastPosition)) lock.onInput()
                    lastPosition = position
                }
            }
        }.onPreviewKeyEvent {
            lock.onInput()
            false
        }
}
