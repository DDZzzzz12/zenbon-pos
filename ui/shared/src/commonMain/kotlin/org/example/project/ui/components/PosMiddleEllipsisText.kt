package org.example.project.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * One line of text that, when too long, loses characters from the middle instead of the end (see
 * [middleEllipsize]). For identifiers whose start is shared and whose end is what differs: generated
 * file names, invoice numbers.
 */
@Composable
fun PosMiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val shown =
            remember(text, style, maxWidth) {
                if (maxWidth == Constraints.Infinity) {
                    text
                } else {
                    middleEllipsize(text, maxWidth) { candidate ->
                        measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width
                    }
                }
            }
        Text(
            text = shown,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
