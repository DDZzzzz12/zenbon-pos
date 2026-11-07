@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.pdf

import org.khronos.webgl.toInt8Array
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray

internal const val PDF_MIME_TYPE = "application/pdf"

/**
 * Wraps [bytes] in an `application/pdf` `Blob` and returns a `blob:` object URL for it; the caller owns
 * revocation. The type is fixed on purpose: a `blob:` URL inherits this panel's origin, so a file the
 * browser would render as active content (an uploaded `.xml` can carry an XHTML `<script>`) must never
 * be handed out this way.
 */
internal fun createPdfObjectUrl(bytes: ByteArray): String {
    val blobParts = JsArray<JsAny?>()
    blobParts[0] = bytes.toInt8Array()
    return URL.createObjectURL(Blob(blobParts, BlobPropertyBag(type = PDF_MIME_TYPE)))
}
