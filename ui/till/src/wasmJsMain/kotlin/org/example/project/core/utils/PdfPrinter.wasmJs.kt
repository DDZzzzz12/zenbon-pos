@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.core.utils

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

private fun newByteArray(size: Int): JsAny = js("new Uint8Array(size)")

private fun setByte(
    array: JsAny,
    index: Int,
    value: Int,
): Unit = js("array[index] = value")

// No secure-context-only APIs (no navigator.clipboard / crypto.randomUUID): a till served over
// plain HTTP on the LAN must still be able to show the receipt. Returns false when the browser
// blocked the new tab (window.open yields null).
private fun openPdfInNewTab(bytes: JsAny): Boolean =
    js(
        """
        (function () {
            var url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
            var w = window.open(url, '_blank');
            if (w === null || w === undefined) { URL.revokeObjectURL(url); return false; }
            setTimeout(function () { URL.revokeObjectURL(url); }, 120000);
            return true;
        })()
        """,
    )

/**
 * Browser "print": opens the PDF in a new tab, where the viewer's own print button takes over.
 *
 * Calls the error callback rather than throwing: every call site already handles `onError`, and a
 * thrown exception from a UI action would take the screen down instead of showing a message. The
 * callback contract carries a plain String and an actual cannot call the composable `str()`, so
 * the message is a stable English fallback, like the other actuals' hardcoded messages.
 */
actual object PdfPrinter {
    actual fun printPdf(
        pdfBytes: ByteArray,
        fileName: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        val opened =
            try {
                val array = newByteArray(pdfBytes.size)
                pdfBytes.forEachIndexed { index, byte -> setByte(array, index, byte.toInt() and 0xFF) }
                openPdfInNewTab(array)
            } catch (e: Throwable) {
                onError("The PDF could not be opened: ${e.message}")
                return
            }
        if (opened) {
            onSuccess()
        } else {
            onError("The browser blocked the PDF window. Allow pop-ups for this site and try again.")
        }
    }
}
