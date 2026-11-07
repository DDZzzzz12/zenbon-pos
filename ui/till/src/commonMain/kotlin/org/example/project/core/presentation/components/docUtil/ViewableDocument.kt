package org.example.project.core.presentation.components.docUtil

/**
 * A document to render, in terms this project owns.
 *
 * Exists so `commonMain` names no third-party viewer type: [DocumentFileViewer] hands the bytes to
 * shared-ui's `PdfViewer` when [isPdf], and shows a "no preview" state otherwise.
 */
data class ViewableDocument(
    val name: String,
    val bytes: ByteArray,
    val mimeType: String = "application/pdf",
) {
    /** Only a PDF can be previewed; MIME parameters (`; charset=…`) and case are ignored. */
    val isPdf: Boolean get() = mimeType.substringBefore(';').trim().equals(PDF_MIME_TYPE, ignoreCase = true)

    // ByteArray compares by identity, so a data class holding one needs these written out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewableDocument) return false
        return name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (name.hashCode() * 31 + mimeType.hashCode()) * 31 + bytes.contentHashCode()

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }
}
