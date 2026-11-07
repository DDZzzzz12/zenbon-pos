package org.example.project.ui.components.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pinch-to-zoom that leaves one-finger scrolling to the list below it.
 *
 * `detectTransformGestures` would also claim one-finger pans, and the scrollable list consumes events before a
 * parent's main pass sees them; so this watches the initial pass and acts (and consumes) only while two or more
 * pointers are down, multiplying [state]'s zoom by each frame's factor. Keyed on [state] (not on a lambda) so a
 * recomposition mid-pinch does not restart the gesture, while a new document's state does.
 */
internal fun Modifier.pdfPinchZoom(state: PdfViewerState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val factor = event.calculateZoom()
                    if (factor != 1f) state.setZoom(state.zoom.value * factor)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
