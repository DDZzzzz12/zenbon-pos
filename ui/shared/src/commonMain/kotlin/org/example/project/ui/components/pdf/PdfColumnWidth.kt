package org.example.project.ui.components.pdf

import androidx.compose.ui.unit.Dp

/**
 * Width of the page column: the page plus its gutters, but never narrower than the viewport, so a page zoomed out
 * below fit-width sits centred in a full-width column instead of at its left edge. Wider (zoomed in), the column
 * outgrows the viewport and pans sideways.
 */
internal fun pdfColumnWidth(
    viewportWidth: Dp,
    pageWidth: Dp,
    gutter: Dp,
): Dp = maxOf(pageWidth + gutter * 2, viewportWidth)
