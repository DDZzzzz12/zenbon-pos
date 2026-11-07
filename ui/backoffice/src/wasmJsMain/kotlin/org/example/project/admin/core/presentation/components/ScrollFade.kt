package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the content at the top edge while [state] can scroll backward and at the bottom edge while
 * it can scroll forward, as a cue that more content lies beyond. Apply it OUTSIDE `verticalScroll`
 * so the fade is drawn over the scrolled content rather than moving with it.
 */
fun Modifier.verticalScrollFade(
    state: ScrollState,
    height: Dp = 40.dp,
): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = height.toPx().coerceAtMost(size.height / 2f)
            if (fadePx <= 0f) return@drawWithContent
            if (state.canScrollBackward) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fadePx),
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollForward) {
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startY = size.height - fadePx,
                            endY = size.height,
                        ),
                    topLeft = Offset(0f, size.height - fadePx),
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
