package org.example.project.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks its font to fit the available width (down to [minFontSize]), then
 * ellipsizes as a last resort. Use for dense card values/labels where truncation would
 * hide meaningful content. Mirrors the StepBased autoSize pattern in PosBottomBar.
 *
 * When [maxFontSize] is unspecified it resolves to the effective style font size
 * (falling back to 16.sp if the style has none), giving "shrink from current size".
 *
 * Pass already-localized strings (resolve with `str(...)` at the call site).
 */
@Composable
fun PosAutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 5.sp,
    maxFontSize: TextUnit = TextUnit.Unspecified,
    stepSize: TextUnit = 0.5.sp,
    maxLines: Int = 1,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val computedMax =
        when {
            !maxFontSize.isUnspecified -> maxFontSize
            !style.fontSize.isUnspecified -> style.fontSize
            else -> 16.sp
        }
    // Guard against an inverted range (min > max), which StepBased does not accept.
    val resolvedMax = if (computedMax.value < minFontSize.value) minFontSize else computedMax
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize =
            TextAutoSize.StepBased(
                minFontSize = minFontSize,
                maxFontSize = resolvedMax,
                stepSize = stepSize,
            ),
    )
}
