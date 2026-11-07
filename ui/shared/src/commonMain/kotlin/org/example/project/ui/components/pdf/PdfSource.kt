package org.example.project.ui.components.pdf

/** Where a PDF comes from: bytes already in memory, or a URL fetched with a bearer token. */
sealed interface PdfSource {
    class Bytes(
        val bytes: ByteArray,
    ) : PdfSource

    class Url(
        val url: String,
        val tokenProvider: PdfTokenProvider,
    ) : PdfSource
}
