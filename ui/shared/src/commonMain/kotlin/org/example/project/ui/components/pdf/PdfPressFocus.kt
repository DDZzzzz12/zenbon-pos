package org.example.project.ui.components.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onPress] for a press that nothing inside handled — on a page, between pages, on the toolbar's empty space.
 *
 * It listens in the main pass, which reaches a parent after its children, and only to presses still unconsumed, so it
 * never takes an event from the list (scroll, fling, wheel, pinch) or from a button or field below it, and consumes
 * nothing itself. A focused child that must not be affected by its own presses marks them with [pdfClaimsPresses].
 */
internal fun Modifier.pdfOnUnclaimedPress(
    key: Any?,
    onPress: () -> Unit,
): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            onPress()
        }
    }

/**
 * Marks every press on this element as handled, after the element's own handling, so a [pdfOnUnclaimedPress] above
 * it ignores it: clicking into the page field to move its caret must not clear the field's focus.
 */
internal fun Modifier.pdfClaimsPresses(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
        }
    }
