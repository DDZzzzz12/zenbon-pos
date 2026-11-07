@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.pdf

import co.touchlab.kermit.Logger
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.url.URL
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

private fun newAbortControllerJs(): JsAny = js("new AbortController()")

private fun abortJs(controller: JsAny): Unit = js("{ controller.abort(); }")

// Listens for `afterprint` on [target] until [controller] aborts. Access to a frame's window can throw (it is
// same-origin for a blob: URL, but a browser's built-in PDF viewer may not expose it), so failure is ignored.
private fun onAfterPrintJs(
    target: JsAny,
    controller: JsAny,
    callback: () -> Unit,
): Unit =
    js(
        """{
            try {
                target.addEventListener('afterprint', function () { callback(); }, { signal: controller.signal });
            } catch (e) { }
        }""",
    )

/**
 * "Drucken" for a PDF: loads it into an invisible, zero-size `<iframe>` and calls the browser's print on
 * it once loaded, which opens the print dialog for the PDF itself. The frame paints nothing, so it needs
 * no positioning over the canvas and no hiding behind dialogs.
 *
 * Removing the frame while its print dialog is open cancels the job, so the frame and its object URL are
 * released by [PrintFrameRelease]: shortly after `afterprint` (listened for on the frame's window and on the
 * page's, since browsers differ in which one fires), at the latest after a generous fallback for browsers that
 * fire neither, at once when the next print starts, and at once on sign-out ([releaseNow], called when the
 * authenticated app leaves the composition) so a signed-out panel keeps no blob of the last document.
 */
object PdfPrint {
    private val log = Logger.withTag("PdfPrint")
    private val releases =
        PrintFrameRelease(
            setTimer = { delayMs, action ->
                window.setTimeout({
                    action()
                    null
                }, delayMs)
            },
            clearTimer = { id -> window.clearTimeout(id) },
        )

    fun print(bytes: ByteArray) {
        val url = createPdfObjectUrl(bytes)
        val printFrame = hiddenFrame()
        val listeners = newAbortControllerJs()
        val afterPrint =
            releases.hold {
                abortJs(listeners)
                printFrame.remove()
                URL.revokeObjectURL(url)
                log.d { "print frame released" }
            }
        onAfterPrintJs(window, listeners, afterPrint)
        printFrame.addEventListener("load", {
            printFrame.contentWindow?.let { frameWindow -> onAfterPrintJs(frameWindow, listeners, afterPrint) }
            printFrame.contentWindow?.focus()
            printFrame.contentWindow?.print()
        })
        printFrame.src = url
        document.body?.appendChild(printFrame)
    }

    /** Frees the last print's frame and object URL now (sign-out); a no-op when nothing is held. */
    fun releaseNow() {
        releases.releaseNow()
    }

    private fun hiddenFrame(): HTMLIFrameElement =
        (document.createElement("iframe") as HTMLIFrameElement).apply {
            setAttribute("aria-hidden", "true")
            tabIndex = -1
            style.position = "fixed"
            style.right = "0"
            style.bottom = "0"
            style.width = "0"
            style.height = "0"
            style.border = "0"
            style.visibility = "hidden"
        }
}
