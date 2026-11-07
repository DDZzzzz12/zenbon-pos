package org.example.project.admin.features.documents.presentation.scanner.imaging

sealed interface PdfWriteResult {
    class Success(
        val bytes: ByteArray,
    ) : PdfWriteResult

    /** The PDF would exceed the cap (the server rejects anything over 20 MB). */
    class TooLarge(
        val sizeBytes: Int,
    ) : PdfWriteResult
}
