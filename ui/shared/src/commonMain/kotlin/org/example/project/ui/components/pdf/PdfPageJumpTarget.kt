package org.example.project.ui.components.pdf

/**
 * The 1-based page a confirmed jump field lands on: the typed number clamped to `1..pageCount`, or [currentPage]
 * when nothing readable was typed. The field shows this value afterwards, so an out-of-range number never stays.
 */
internal fun pdfPageJumpTarget(
    typed: String,
    currentPage: Int,
    pageCount: Int,
): Int {
    val lastPage = pageCount.coerceAtLeast(1)
    val requested = typed.trim().toLongOrNull() ?: return currentPage.coerceIn(1, lastPage)
    return requested.coerceIn(1L, lastPage.toLong()).toInt()
}
