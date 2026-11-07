package org.example.project.ui.components.pdf

/**
 * Whether trying again can help: a dropped connection or a session that could not be renewed in time may
 * succeed on a second attempt; a damaged, empty, encrypted, missing (404), refused (403) or non-PDF file will
 * fail the same way again.
 */
fun PdfLoadError.isRetryable(): Boolean = this == PdfLoadError.Network || this == PdfLoadError.Unauthorized
