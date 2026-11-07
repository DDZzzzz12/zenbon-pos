package org.example.project.ui.components.pdf

/**
 * The page field's text, and whether it holds an edit the user has not confirmed yet.
 *
 * Only an unconfirmed edit is reverted on focus loss. Go replaces the edit with [showing] the target, so the focus
 * loss that Go itself causes (synchronously, before the toolbar recomposes with the new page) cannot overwrite the
 * clamped target with the page the field was composed for — which never changes when the document fits the viewport.
 */
internal data class PdfPageJumpEdit(
    val text: String,
    val editing: Boolean = false,
) {
    fun typed(raw: String): PdfPageJumpEdit = PdfPageJumpEdit(raw.filter(Char::isDigit).take(MAX_PAGE_DIGITS), editing = true)

    /** The page Go lands on: see [pdfPageJumpTarget]. */
    fun target(
        currentPage: Int,
        pageCount: Int,
    ): Int = pdfPageJumpTarget(text, currentPage, pageCount)

    /** Leaving the field: an unconfirmed edit is dropped in favour of [currentPage]; anything else stays. */
    fun focusLost(currentPage: Int): PdfPageJumpEdit = if (editing) showing(currentPage) else this

    /** Esc: the edit is cancelled outright; the field shows [currentPage] again, typed or not. */
    fun cancel(currentPage: Int): PdfPageJumpEdit = showing(currentPage)

    companion object {
        private const val MAX_PAGE_DIGITS = 5

        fun showing(page: Int): PdfPageJumpEdit = PdfPageJumpEdit(page.toString())
    }
}
