package org.example.project.admin.features.documents.presentation

/** How a document's file can be previewed in the browser pane. */
enum class DocumentPreviewKind { PDF, NONE }

/**
 * The one preview decision of the document detail pane and the invoice capture screen.
 *
 * Only a PDF has an in-page preview (the shared `PdfViewer`). An unknown media type keeps the PDF
 * branch: on the detail pane the document has not loaded yet (switching panes mid-load must not
 * flash the "no preview" text at every PDF), and on the capture screen the invoice-keyed path of a
 * Storno correction loads only the bytes, of a Beleg that has always been a PDF scan. Anything else
 * known (an XRechnung XML, an MT940 text file, an image) is not previewable here.
 */
fun previewKind(mediaType: String?): DocumentPreviewKind =
    if (mediaType == null || mediaType == "application/pdf") {
        DocumentPreviewKind.PDF
    } else {
        DocumentPreviewKind.NONE
    }
