package org.example.project.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal actual fun Modifier.horizontalWheelDrive(state: ScrollState): Modifier =
    pointerInput(state) {
        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                    val delta = event.changes.first().scrollDelta
                    val amount =
                        wheelScrollAmount(
                            deltaX = delta.x,
                            deltaY = delta.y,
                            canScrollForward = state.canScrollForward,
                            canScrollBackward = state.canScrollBackward,
                        )
                    if (amount != null) {
                        this@coroutineScope.launch { state.scrollBy(amount * density) }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
