package org.example.project.ui.components.pdf

/**
 * The 0-based page the indicator names: the first visible one, except once the list is scrolled to its end, where
 * the last visible page is named — a short last page can never reach the top, so it would otherwise be unreachable
 * by the indicator. A list that fits entirely (cannot scroll either way) stays on its first page.
 */
internal fun pdfTopPageIndex(
    firstVisible: Int,
    lastVisible: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Int = if (!canScrollForward && canScrollBackward) lastVisible else firstVisible
