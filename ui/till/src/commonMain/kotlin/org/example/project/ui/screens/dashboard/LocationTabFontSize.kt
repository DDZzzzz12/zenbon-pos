package org.example.project.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The one font size every location tab uses: the largest size, from [style]'s down to [minFontSize],
 * at which the longest label still fits a tab. Each tab used to shrink on its own, so at a large font
 * "Außenbereich" came out small beside a full-size "Abholung".
 */
@Composable
internal fun rememberLocationTabFontSize(
    labels: List<String>,
    tabWidth: Dp,
    style: TextStyle,
    minFontSize: TextUnit,
): TextUnit {
    val measurer = rememberTextMeasurer()
    val widthPx = with(LocalDensity.current) { tabWidth.roundToPx() }
    return remember(labels, widthPx, style, minFontSize) {
        commonFitFontSize(labels, style.fontSize.value, minFontSize.value) { label, size ->
            measurer.fits(label, style, size, widthPx)
        }.sp
    }
}

/** Pure search: the largest size in [minSize, maxSize] (0.5 steps) at which every label [fits]. */
internal fun commonFitFontSize(
    labels: List<String>,
    maxSize: Float,
    minSize: Float,
    fits: (label: String, size: Float) -> Boolean,
): Float {
    var size = maxSize
    while (size > minSize && labels.any { !fits(it, size) }) size -= 0.5f
    return maxOf(size, minSize)
}

// The selected tab is semi-bold and so the widest it will ever be: measure that.
private fun TextMeasurer.fits(
    label: String,
    style: TextStyle,
    size: Float,
    widthPx: Int,
): Boolean =
    measure(
        text = label,
        style = style.copy(fontSize = size.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        softWrap = false,
    ).size.width <= widthPx
