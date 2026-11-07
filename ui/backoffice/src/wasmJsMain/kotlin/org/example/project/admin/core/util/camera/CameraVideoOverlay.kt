@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.browser.document
import org.example.project.admin.core.util.dom.LocalDomOverlayController
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Shows [session]'s `<video>` over the Compose canvas. Compose/wasm has one `<canvas>` and no DOM
 * interop, so the element is positioned absolutely from the reserved [Box]'s bounds: bounds are device
 * pixels (divide by density for CSS px), geometry is written straight from the layout pass (an effect
 * would run after the canvas painted, so the video would trail every resize), and the element hides
 * while a dialog or the phone drawer is open (`BlockDomOverlayWhileVisible`) because a DOM element
 * always paints above the canvas.
 */
@Composable
fun CameraVideoOverlay(
    session: CameraSession,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val controller = LocalDomOverlayController.current
    val density = LocalDensity.current.density

    DisposableEffect(session) {
        document.body?.appendChild(session.video)
        onDispose { session.video.remove() }
    }

    LaunchedEffect(visible, controller.isBlocked) {
        session.video.style.visibility = if (visible && !controller.isBlocked) "visible" else "hidden"
    }

    Box(
        modifier =
            modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                session.video.style.left = "${bounds.left / density}px"
                session.video.style.top = "${bounds.top / density}px"
                session.video.style.width = "${bounds.width / density}px"
                session.video.style.height = "${bounds.height / density}px"
            },
    )
}
