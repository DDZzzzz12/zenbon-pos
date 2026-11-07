package org.example.project.ui.components.pdf

import androidx.compose.ui.unit.Dp

/** One page's place in the list: which page, its laid-out width and the pixel width to render it at. */
internal data class PdfPageSlot(
    val pageIndex: Int,
    val pageCount: Int,
    val size: PdfPageSize,
    val widthDp: Dp,
    val renderWidthPx: Int,
)
