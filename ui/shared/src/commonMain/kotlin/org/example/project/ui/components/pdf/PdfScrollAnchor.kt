package org.example.project.ui.components.pdf

import kotlin.math.roundToInt

/**
 * A reading position that survives a change of page size: a page, and how far down it the viewport's top sits as a
 * fraction of that page's height. The list keeps its offset in pixels, so when the pages grow or shrink (the viewport
 * gets wider or narrower, or the zoom changes) the same pixel offset lands elsewhere — on a wider window, in the page
 * above. Capturing the fraction before the change and restoring it against the new height keeps the page in view.
 */
internal data class PdfScrollAnchor(
    val pageIndex: Int,
    /** In `[0, 1)`: never a whole page, so the anchor cannot drift into the next page. */
    val fraction: Float,
) {
    /** The pixel offset into a page now [pageHeightPx] tall; 0 for a page without height. */
    fun offsetPx(pageHeightPx: Int): Int {
        if (pageHeightPx <= 0) return 0
        return (fraction * pageHeightPx).roundToInt().coerceIn(0, pageHeightPx - 1)
    }

    companion object {
        private const val MAX_FRACTION = 0.9999f

        /** The first visible page [pageIndex], scrolled [offsetPx] into its [pageHeightPx]. */
        fun capture(
            pageIndex: Int,
            offsetPx: Int,
            pageHeightPx: Int,
        ): PdfScrollAnchor {
            val fraction = if (pageHeightPx <= 0) 0f else offsetPx.toFloat() / pageHeightPx
            return PdfScrollAnchor(pageIndex.coerceAtLeast(0), fraction.coerceIn(0f, MAX_FRACTION))
        }

        /**
         * The anchor for the page the indicator names ([pdfTopPageIndex]): [firstVisible] when it is that page;
         * otherwise (a list scrolled to its end names its last page) the named page, at its top — the list scrolls
         * as far towards it as it can, so the indicator names the same page after the change.
         */
        fun forNamedPage(
            namedPage: Int,
            firstVisible: PdfScrollAnchor,
        ): PdfScrollAnchor = if (namedPage == firstVisible.pageIndex) firstVisible else PdfScrollAnchor(namedPage, 0f)
    }
}
