package org.example.project.ui.components.pdf

/** Supplies the bearer token for a [PdfSource.Url]; [forceRefresh] asks for a new token after a 401. */
fun interface PdfTokenProvider {
    suspend fun token(forceRefresh: Boolean): String?
}
