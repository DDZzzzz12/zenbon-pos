package org.example.project.ui.components

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The outcome of [fitHeaderFontSize]: the size to draw at, and whether the longest word fits at it. */
data class HeaderFontFit(
    val fontSize: TextUnit,
    val fitsAWord: Boolean,
)

/**
 * Scales [base] down so a word measured [wordWidthPx] wide at [base] fits [availablePx], floored at
 * [min]. Unbounded or unmeasured widths, and a word that already fits, keep [base]. Pure, so the
 * arithmetic is testable without a layout pass.
 */
fun fitHeaderFontSize(
    base: TextUnit,
    wordWidthPx: Int,
    availablePx: Int,
    min: TextUnit,
): HeaderFontFit {
    if (!base.isSp || wordWidthPx <= 0 || availablePx <= 0 || availablePx == Int.MAX_VALUE) {
        return HeaderFontFit(base, fitsAWord = true)
    }
    if (wordWidthPx <= availablePx) return HeaderFontFit(base, fitsAWord = true)
    // A hair under the exact ratio: glyph advances do not scale perfectly linearly.
    val scaled = base.value * availablePx / wordWidthPx * 0.97f
    return if (scaled >= min.value) {
        HeaderFontFit(scaled.sp, fitsAWord = true)
    } else {
        HeaderFontFit(min, fitsAWord = false)
    }
}
