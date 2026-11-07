/**
 * Platform-specific PDF printing utility.
 *
 * Provides a common interface for printing PDFs across
 * different platforms (Desktop, Android, iOS) with appropriate
 * platform-specific implementations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

/**
 * Platform-specific PDF printer object (singleton).
 *
 * Implementations handle PDF printing appropriate for each platform:
 * - Desktop: Opens system print dialog
 * - Android: Opens Android print framework
 * - iOS: Opens iOS print controller
 */
expect object PdfPrinter {
    /**
     * Print a PDF file using platform-specific print dialog.
     *
     * @param pdfBytes PDF file content as ByteArray
     * @param fileName Filename for print job identification
     * @param onSuccess Callback invoked when print dialog is opened successfully
     * @param onError Callback invoked when printing fails with error message
     */
    fun printPdf(
        pdfBytes: ByteArray,
        fileName: String,
        onSuccess: () -> Unit = {},
        onError: (error: String) -> Unit = {},
    )
}
