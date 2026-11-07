package org.example.project.core.utils

import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable
import java.awt.Desktop
import java.awt.print.PrinterJob
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.print.PrintServiceLookup
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.JobName

/**
 * Desktop implementation of PdfPrinter using Apache PDFBox + Java PrinterJob.
 *
 * This implementation shows a native print dialog on all platforms (Linux, Windows, macOS)
 * by using Java's PrinterJob API combined with PDFBox for PDF rendering.
 *
 * The PrinterJob.printDialog() method works reliably across all platforms, unlike
 * Desktop.Action.PRINT which is unsupported on most Linux systems.
 */
actual object PdfPrinter {
    actual fun printPdf(
        pdfBytes: ByteArray,
        fileName: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        try {
            // Check if any print services are available
            val printServices = PrintServiceLookup.lookupPrintServices(null, null)

            if (printServices.isEmpty()) {
                // No printers found - fall back to opening in viewer
                println("No print services found. Falling back to PDF viewer.")
                openInViewer(
                    pdfBytes = pdfBytes,
                    fileName = fileName,
                    onSuccess = onSuccess,
                    onError = {
                        onError("Kein Drucker gefunden. PDF wurde im Viewer geöffnet - bitte dort drucken (Strg+P).")
                    },
                )
                return
            }

            // Load PDF document using PDFBox
            val document = Loader.loadPDF(pdfBytes)

            document.use { doc ->
                // Create PrinterJob and configure it
                val printerJob = PrinterJob.getPrinterJob()

                // Set the document to print using PDFBox's PDFPageable
                printerJob.setPageable(PDFPageable(doc))

                // Set job name for the print queue
                val attributes = HashPrintRequestAttributeSet()
                attributes.add(JobName(fileName.removeSuffix(".pdf"), Locale.getDefault()))
                printerJob.jobName = fileName.removeSuffix(".pdf")

                // Show native print dialog - this works on Linux, Windows, and macOS
                if (printerJob.printDialog(attributes)) {
                    // User clicked Print - execute the print job
                    printerJob.print(attributes)
                    onSuccess()
                } else {
                    // User cancelled the print dialog
                    onSuccess() // Not an error, user just cancelled
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // If printing fails, try to open in viewer as fallback
            println("Print failed: ${e.message}. Falling back to PDF viewer.")
            openInViewer(
                pdfBytes = pdfBytes,
                fileName = fileName,
                onSuccess = onSuccess,
                onError = { onError("Druckfehler: ${e.message}") },
            )
        }
    }

    /**
     * Alternative method: Open PDF in system viewer for manual printing.
     * Use this as a fallback if PDFBox printing fails.
     */
    fun openInViewer(
        pdfBytes: ByteArray,
        fileName: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        try {
            // Create temporary file
            val tempFile = File.createTempFile("view_${fileName.removeSuffix(".pdf")}_", ".pdf")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { output ->
                output.write(pdfBytes)
            }

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(tempFile)
                onSuccess()
            } else {
                // Try xdg-open on Linux
                val process =
                    ProcessBuilder("xdg-open", tempFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                Thread.sleep(100)
                if (process.isAlive || process.exitValue() == 0) {
                    onSuccess()
                } else {
                    onError("Konnte PDF nicht öffnen")
                }
            }
        } catch (e: Exception) {
            onError("Fehler beim Öffnen: ${e.message}")
        }
    }
}
