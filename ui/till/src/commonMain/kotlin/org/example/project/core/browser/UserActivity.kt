package org.example.project.core.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType

/**
 * Whether a pointer event is a person doing something, as opposed to Compose re-dispatching a hover.
 *
 * Compose synthesises Move/Enter/Exit events at a stationary pointer whenever the content under it
 * changes (a ticking status-bar clock does this every second), so counting every event keeps an idle
 * till awake forever. Press, Release and Scroll are always real; a Move is real only when the
 * position changed since the previous event; Enter, Exit and anything else are never counted.
 */
internal fun isUserActivity(
    type: PointerEventType,
    position: Offset?,
    lastPosition: Offset?,
): Boolean =
    when (type) {
        PointerEventType.Press, PointerEventType.Release, PointerEventType.Scroll -> true
        PointerEventType.Move -> position != lastPosition
        else -> false
    }
