package org.example.project.ui.components.pdf

private const val PDF_EXTENSION = ".pdf"

/**
 * The name a downloaded PDF is saved under: [name] itself when it already ends in `.pdf` (in any case), otherwise
 * [name] with `.pdf` appended — a host may pass a display title such as "PDF-Vorschau", and a file without the
 * extension does not open as a PDF. Nothing else is changed.
 */
internal fun pdfDownloadFileName(name: String): String = if (name.endsWith(PDF_EXTENSION, ignoreCase = true)) name else name + PDF_EXTENSION
