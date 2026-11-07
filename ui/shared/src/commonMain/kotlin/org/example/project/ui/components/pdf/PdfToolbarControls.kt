package org.example.project.ui.components.pdf

/**
 * Which of the viewer's toolbar controls are live, derived from the open state alone. Until a document is
 * [PdfLoadState.Ready] — loading, or failed for any [PdfLoadError] — every document control is off and the page
 * indicator is not shown (there is no page to name, so never "1 / 0"). [actionsEnabled] is what a host's own
 * actions (print, open in a new tab) receive. Close is not part of this: it is always offered.
 */
data class PdfToolbarControls(
    val showPageIndicator: Boolean,
    val pageCount: Int,
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
    val zoomOutEnabled: Boolean,
    val zoomInEnabled: Boolean,
    val fitWidthEnabled: Boolean,
    val downloadEnabled: Boolean,
    val actionsEnabled: Boolean,
) {
    companion object {
        val Disabled =
            PdfToolbarControls(
                showPageIndicator = false,
                pageCount = 0,
                previousEnabled = false,
                nextEnabled = false,
                zoomOutEnabled = false,
                zoomInEnabled = false,
                fitWidthEnabled = false,
                downloadEnabled = false,
                actionsEnabled = false,
            )

        fun of(
            loadState: PdfLoadState,
            currentPage: Int,
            zoom: Float,
            downloading: Boolean,
        ): PdfToolbarControls {
            val ready = loadState as? PdfLoadState.Ready ?: return Disabled
            val pageCount = ready.info.pages.size
            return PdfToolbarControls(
                showPageIndicator = true,
                pageCount = pageCount,
                previousEnabled = currentPage > 1,
                nextEnabled = currentPage < pageCount,
                zoomOutEnabled = zoom > PdfZoom.MIN,
                zoomInEnabled = zoom < PdfZoom.MAX,
                fitWidthEnabled = zoom != PdfZoom.FIT_WIDTH,
                downloadEnabled = !downloading,
                actionsEnabled = true,
            )
        }
    }
}
