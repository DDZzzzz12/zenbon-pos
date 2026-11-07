package org.example.project.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.layout.arrowStepPx
import org.example.project.ui.layout.horizontalWheelDrive

/**
 * The only sanctioned horizontally scrolling strip. A raw `horizontalScroll`/`LazyRow` ignores a
 * mouse wheel on wasm (the page scrolls instead), so on wasm this wrapper maps the wheel to horizontal
 * scrolling and falls through at either end; on Android and desktop a plain vertical wheel does not scroll the strip (only shift+wheel, trackpad, touch or the arrow buttons) and is left to the page. Small
 * arrow buttons (2 wheel ticks per click) show once a mouse has been over it. Touch drag is unchanged
 * and a trackpad's horizontal deltas are native; on wasm a VERTICAL trackpad or wheel delta over the
 * strip scrolls the strip first, until its end. Prefer reflowing content (FlowRow, cards, stacked
 * fields) and use this only where a strip is genuinely unavoidable. Width is the caller's choice:
 * pass `Modifier.fillMaxWidth()` to fill, or nothing to wrap the content (needed next to a weighted
 * sibling).
 *
 * [horizontalArrangement] is spacing only (e.g. `Arrangement.spacedBy`): the inner Row wraps its
 * content, so End/Center/SpaceBetween have no effect.
 *
 * The arrows float over the strip's edges by default. With [arrowsBesideContent] they take their own slots
 * at either side instead, so they never cover the content (a toolbar whose edge item is an input field); the
 * slots appear together once a mouse is seen and the strip overflows, the arrow for a direction that cannot
 * scroll shown disabled, which keeps the strip's width steady while it scrolls.
 */
@Composable
fun PosHorizontalScroller(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    arrowsBesideContent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var mouseSeen by remember { mutableStateOf(false) }
    val scrollBack: () -> Unit = { scope.launch { state.animateScrollBy(-arrowStepPx(density)) } }
    val scrollForward: () -> Unit = { scope.launch { state.animateScrollBy(arrowStepPx(density)) } }

    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Enter && event.changes.any { it.type == PointerType.Mouse }) {
                        mouseSeen = true
                    }
                }
            }
        },
    ) {
        val strip: @Composable (Modifier) -> Unit = { stripModifier ->
            Row(
                modifier = stripModifier.horizontalWheelDrive(state).horizontalScroll(state),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content,
            )
        }
        if (arrowsBesideContent) {
            val showSlots = mouseSeen && (state.canScrollBackward || state.canScrollForward)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSlots) {
                    ScrollArrow(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        description = str(CommonKeys.scrollLeft),
                        enabled = state.canScrollBackward,
                        onClick = scrollBack,
                    )
                }
                strip(Modifier.weight(1f, fill = false))
                if (showSlots) {
                    ScrollArrow(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        description = str(CommonKeys.scrollRight),
                        enabled = state.canScrollForward,
                        onClick = scrollForward,
                    )
                }
            }
        } else {
            strip(Modifier)
            if (mouseSeen && state.canScrollBackward) {
                ScrollArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    description = str(CommonKeys.scrollLeft),
                    enabled = true,
                    onClick = scrollBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            if (mouseSeen && state.canScrollForward) {
                ScrollArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    description = str(CommonKeys.scrollRight),
                    enabled = true,
                    onClick = scrollForward,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun ScrollArrow(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 2.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
    ) {
        PosIconButton(icon = icon, contentDescription = description, onClick = onClick, enabled = enabled, size = 32.dp)
    }
}
