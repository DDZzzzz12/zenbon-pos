package org.example.project.core.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Android implementation of PdfPrinter using Android Print Framework.
 *
 * Shows the native Android print dialog where users can select printers,
 * configure print settings (copies, color, paper size), and print or save to PDF.
 *
 * Note: PrintManager.print() requires an Activity context, not just application context.
 */
actual object PdfPrinter {
    private var activityRef: WeakReference<Activity>? = null

    /**
     * Initialize the printer with an Activity context.
     * Must be called from Activity onCreate.
     *
     * Note: Uses WeakReference to avoid memory leaks.
     */
    fun initialize(context: Context) {
        val activity = context.findActivity()
        if (activity != null) {
            activityRef = WeakReference(activity)
        }
    }

    /**
     * Update the activity reference. Call this from your Activity's onResume
     * to ensure we have a valid reference.
     */
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    actual fun printPdf(
        pdfBytes: ByteArray,
        fileName: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        val activity = activityRef?.get()
        if (activity == null) {
            onError("Keine Activity verfügbar. Bitte App neu starten.")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Create temporary file for printing
                val tempFile = File(activity.cacheDir, "print_$fileName")
                FileOutputStream(tempFile).use { output ->
                    output.write(pdfBytes)
                }

                // Get page count from PDF
                val pageCount = getPdfPageCount(tempFile)

                // Switch to main thread to show print dialog
                launch(Dispatchers.Main) {
                    try {
                        // Get PrintManager from Activity context (required!)
                        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager

                        val printAdapter =
                            PdfPrintDocumentAdapter(
                                pdfFile = tempFile,
                                documentName = fileName.removeSuffix(".pdf"),
                                pageCount = pageCount,
                                onPrintFinished = {
                                    // Clean up temp file after printing
                                    GlobalScope.launch(Dispatchers.IO) {
                                        kotlinx.coroutines.delay(5000)
                                        tempFile.delete()
                                    }
                                },
                            )

                        // Show native print dialog
                        printManager.print(
                            fileName.removeSuffix(".pdf"),
                            printAdapter,
                            PrintAttributes
                                .Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .build(),
                        )

                        onSuccess()
                    } catch (e: Exception) {
                        tempFile.delete()
                        onError("Druckfehler: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                onError("PDF konnte nicht vorbereitet werden: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Get the number of pages in a PDF file.
     */
    private fun getPdfPageCount(pdfFile: File): Int =
        try {
            val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val count = renderer.pageCount
            renderer.close()
            fileDescriptor.close()
            count
        } catch (e: Exception) {
            1 // Default to 1 page if we can't determine
        }

    /**
     * Find Activity from Context by traversing the context wrapper chain.
     */
    private fun Context.findActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            }
            context = context.baseContext
        }
        return null
    }
}

/**
 * PrintDocumentAdapter for printing existing PDF files.
 *
 * This adapter reads an existing PDF file and provides it to the Android Print Framework.
 */
private class PdfPrintDocumentAdapter(
    private val pdfFile: File,
    private val documentName: String,
    private val pageCount: Int,
    private val onPrintFinished: () -> Unit,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }

        val info =
            PrintDocumentInfo
                .Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount)
                .build()

        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }

        try {
            // Copy PDF file to the print destination
            FileInputStream(pdfFile).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }

            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
            e.printStackTrace()
        }
    }

    override fun onFinish() {
        super.onFinish()
        onPrintFinished()
    }
}
