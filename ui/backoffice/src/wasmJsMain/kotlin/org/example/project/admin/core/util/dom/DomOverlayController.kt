package org.example.project.admin.core.util.dom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Counts the Compose surfaces (dialogs, the phone drawer) currently open above a DOM element laid over
 * the canvas.
 *
 * Compose/wasm paints into one `<canvas>`, so a DOM element positioned over it always draws above
 * anything Compose renders, modal scrims included. The one such element left is the document
 * scanner's camera `<video>` ([org.example.project.admin.core.util.camera.CameraVideoOverlay]), which
 * hides while the count is above zero. PDFs no longer need this: the shared `PdfViewer` paints into
 * the canvas like everything else.
 */
class DomOverlayController {
    var blockingCount by mutableStateOf(0)
        private set

    val isBlocked: Boolean get() = blockingCount > 0

    fun block() {
        blockingCount++
    }

    fun unblock() {
        if (blockingCount > 0) blockingCount--
    }
}

val LocalDomOverlayController = compositionLocalOf { DomOverlayController() }

/**
 * Marks the composable it's called from as something that must draw above a DOM overlay (a dialog
 * or the phone drawer that can open over the scanner's camera). Call at the top of that composable's
 * body: blocking starts when it enters composition and ends when it leaves, however it leaves.
 */
@Composable
fun BlockDomOverlayWhileVisible() {
    val controller = LocalDomOverlayController.current
    DisposableEffect(Unit) {
        controller.block()
        onDispose { controller.unblock() }
    }
}
