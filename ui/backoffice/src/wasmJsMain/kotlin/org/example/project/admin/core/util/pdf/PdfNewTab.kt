@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.pdf

import kotlinx.browser.window
import org.w3c.dom.Window
import org.w3c.dom.url.URL
import kotlin.js.ExperimentalWasmJsInterop

private const val REVOKE_AFTER_MS = 60_000

/**
 * A browser tab opened for a PDF before its bytes are in hand, so the browser's own viewer can show it
 * ("In neuem Tab öffnen"). The tab must be opened straight from the click handler — a popup opened after
 * the file has been fetched is no longer tied to the click and is blocked — so [reservePdfTab] opens an
 * empty tab synchronously, and [show] points it at the file once it has arrived.
 *
 * Only `application/pdf` is ever opened: see [createPdfObjectUrl]. The object URL is revoked a minute
 * later, once the tab has certainly loaded it.
 */
class PendingPdfTab internal constructor(
    private val tab: Window,
) {
    fun show(bytes: ByteArray) {
        val url = createPdfObjectUrl(bytes)
        tab.location.href = url
        window.setTimeout({
            URL.revokeObjectURL(url)
            null
        }, REVOKE_AFTER_MS)
    }

    /** The file could not be fetched: close the empty tab rather than leave it behind. */
    fun cancel() {
        tab.close()
    }
}

/**
 * Opens the empty tab for a document of [mimeType]; call it synchronously from a click handler. Null
 * when the document is no PDF (nothing is opened) or the browser blocked the popup.
 */
fun reservePdfTab(mimeType: String?): PendingPdfTab? {
    if (mimeType != PDF_MIME_TYPE) return null
    return window.open("", "_blank")?.let { PendingPdfTab(it) }
}
