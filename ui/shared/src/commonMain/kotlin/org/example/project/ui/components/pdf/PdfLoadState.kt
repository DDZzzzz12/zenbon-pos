package org.example.project.ui.components.pdf

/** Where the viewer's document is in its one-time open. */
sealed interface PdfLoadState {
    data object Loading : PdfLoadState

    data class Ready(
        val info: PdfDocumentInfo,
    ) : PdfLoadState

    data class Failed(
        val error: PdfLoadError,
    ) : PdfLoadState
}
