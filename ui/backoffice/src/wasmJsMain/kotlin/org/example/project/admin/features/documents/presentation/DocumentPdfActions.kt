package org.example.project.admin.features.documents.presentation

import co.touchlab.kermit.Logger
import org.example.project.admin.core.util.pdf.PdfPrint
import org.example.project.admin.core.util.pdf.reservePdfTab

/**
 * Print and "open in a new tab" for the PDF on the document detail screen, offered in the viewer's
 * toolbar. Both need the file in hand, which the viewer keeps to itself, so they fetch it on press
 * through the app's authenticated client ([DocumentDetailViewModel.withFileBytes]).
 */
class DocumentPdfActions(
    private val documentId: Long,
    private val viewModel: DocumentDetailViewModel,
) {
    private val log = Logger.withTag("documentPdfActions")

    fun print() {
        viewModel.withFileBytes(documentId, onFailed = {}) { bytes -> PdfPrint.print(bytes) }
    }

    /**
     * The tab is opened right here, inside the click, and filled once the file has arrived — a tab
     * opened after the fetch would be a popup the browser blocks. Only a PDF is ever opened.
     */
    fun openInTab(mediaType: String) {
        val tab = reservePdfTab(mediaType)
        if (tab == null) {
            log.w { "document $documentId not opened in a tab: no PDF ($mediaType) or the popup was blocked" }
            return
        }
        viewModel.withFileBytes(documentId, onFailed = tab::cancel, onBytes = tab::show)
    }
}
