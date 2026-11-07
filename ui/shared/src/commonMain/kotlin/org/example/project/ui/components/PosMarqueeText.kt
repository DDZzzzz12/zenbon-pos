package org.example.project.ui.components

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-line text that horizontally scrolls ("rolling") when, and only when, it would
 * overflow its width. Use for headers that must show their full content — TopAppBar
 * titles and [PosDialog] headers. Text that fits stays static.
 *
 * Pass already-localized strings (resolve with `str(...)` at the call site).
 */
@Composable
fun PosMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    iterations: Int = Int.MAX_VALUE,
    repeatDelayMillis: Int = 1200,
    initialDelayMillis: Int = 1200,
    spacing: MarqueeSpacing = MarqueeSpacing(48.dp),
    velocity: Dp = 40.dp,
) {
    Text(
        text = text,
        modifier =
            modifier.basicMarquee(
                iterations = iterations,
                repeatDelayMillis = repeatDelayMillis,
                initialDelayMillis = initialDelayMillis,
                spacing = spacing,
                velocity = velocity,
            ),
        style = style,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
    )
}
