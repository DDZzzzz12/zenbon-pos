package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Strings of shared-ui's `PdfViewer`, used by composeApp and adminApp alike. */
object PdfViewerKeys {
    // Toolbar
    val previousPage = StringKey("pdf_viewer.previous_page")
    val nextPage = StringKey("pdf_viewer.next_page")

    /** Accessible name of a page and of the jump-to-page field: {0} = page number, {1} = page count. */
    val pageOf = StringKey("pdf_viewer.page_of")

    /** Visible suffix after the jump-to-page field: {0} = page count. */
    val ofTotal = StringKey("pdf_viewer.of_total")
    val zoomIn = StringKey("pdf_viewer.zoom_in")
    val zoomOut = StringKey("pdf_viewer.zoom_out")
    val fitWidth = StringKey("pdf_viewer.fit_width")
    val download = StringKey("pdf_viewer.download")
    val downloadFailed = StringKey("pdf_viewer.download_failed")
    val downloadFailedMessage = StringKey("pdf_viewer.download_failed_message")

    // States
    val loading = StringKey("pdf_viewer.loading")
    val pageRenderFailed = StringKey("pdf_viewer.page_render_failed")

    // One message per PdfLoadError
    val errorCorrupt = StringKey("pdf_viewer.error_corrupt")
    val errorPasswordProtected = StringKey("pdf_viewer.error_password_protected")
    val errorEmpty = StringKey("pdf_viewer.error_empty")
    val errorNotPdf = StringKey("pdf_viewer.error_not_pdf")
    val errorUnauthorized = StringKey("pdf_viewer.error_unauthorized")
    val errorForbidden = StringKey("pdf_viewer.error_forbidden")
    val errorNetwork = StringKey("pdf_viewer.error_network")
    val errorNotFound = StringKey("pdf_viewer.error_not_found")
    val errorUnsupported = StringKey("pdf_viewer.error_unsupported")
}
