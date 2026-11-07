/**
 * Browser (Kotlin/Wasm) implementation of FileSaver.
 *
 * A browser "save" is an object-URL download: the bytes become a Blob, an anchor carrying the
 * `download` attribute is clicked, and the browser writes the file wherever the user's download
 * settings point. There is no file system to address and no share sheet, so [shareFile] performs
 * the same download.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.core.utils

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.toInt8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.set

/**
 * Browser implementation of FileSaver as a singleton object.
 *
 * The browser owns the destination — the page never learns where the file landed, and cannot
 * choose it — so the "path" reported to callers is just the filename.
 */
actual object FileSaver {
    /**
     * How long the object URL is kept alive before it is revoked.
     *
     * The download is not necessarily finished when [HTMLAnchorElement.click] returns: the browser
     * may still be reading the blob. Revoking immediately can therefore truncate or cancel a large
     * export, so the revoke is deferred — long enough for the browser to have taken the data,
     * short enough that the blob is not retained for the life of the page.
     */
    private const val OBJECT_URL_LIFETIME_MS = 60_000

    /**
     * Save a file by triggering a browser download.
     *
     * @param data File content as ByteArray
     * @param filename Filename offered to the browser via the anchor's `download` attribute
     * @param mimeType MIME type stamped on the Blob
     * @param onSuccess Called with the filename (the browser owns the real path)
     * @param onError Called with an error message if the download could not be started
     * @param onCancelled Never called: a browser download has no dialog to cancel
     */
    actual fun saveFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: (filePath: String) -> Unit,
        onError: (error: String) -> Unit,
        onCancelled: (() -> Unit)?,
    ) {
        try {
            startDownload(data, filename, mimeType)
            onSuccess(filename)
        } catch (e: Throwable) {
            onError("Failed to save file: ${e.message}")
        }
    }

    /**
     * Share a file. The browser has no share sheet available to a page, so this is the same
     * download as [saveFile] — the caller's intent (get this file to the user) is satisfied.
     *
     * @param data File content as ByteArray
     * @param filename Filename offered to the browser
     * @param mimeType MIME type stamped on the Blob
     * @param onSuccess Called once the download has been started
     * @param onError Called with an error message if the download could not be started
     */
    actual fun shareFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        try {
            startDownload(data, filename, mimeType)
            onSuccess()
        } catch (e: Throwable) {
            onError("Failed to share file: ${e.message}")
        }
    }

    /**
     * Wrap [data] in a Blob and click a throwaway anchor pointing at its object URL.
     *
     * The anchor is attached to the document before the click and detached after: a detached
     * anchor works in current browsers, but attaching is what the HTML spec's activation
     * behaviour is defined against, and it costs nothing.
     */
    private fun startDownload(
        data: ByteArray,
        filename: String,
        mimeType: String,
    ) {
        val blobParts = JsArray<JsAny?>()
        blobParts[0] = data.toInt8Array()
        val url = URL.createObjectURL(Blob(blobParts, BlobPropertyBag(type = mimeType)))

        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = url
        anchor.download = filename

        val body = document.body
        body?.appendChild(anchor)
        anchor.click()
        body?.removeChild(anchor)

        window.setTimeout({
            URL.revokeObjectURL(url)
            null
        }, OBJECT_URL_LIFETIME_MS)
    }
}
