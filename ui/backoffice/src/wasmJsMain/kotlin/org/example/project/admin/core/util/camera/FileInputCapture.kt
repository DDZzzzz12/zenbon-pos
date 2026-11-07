@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.camera

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.project.core.utils.file.FilePickResult
import org.example.project.core.utils.file.PickedFile
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Opens the device's own camera app (mobile) or an image picker (desktop) via
 * an `<input type=file capture=environment>` that accepts any image type. With [capture] false the
 * `capture` attribute is left off, so phones offer the gallery and files instead of only the camera. This is a plain form control, not a
 * secure-context API, so it is the fallback on `http://<LAN IP>` where `getUserMedia` is missing.
 * Whether it works over plain http on iOS and Android is reported but NOT verified: needs a device test.
 */
suspend fun captureViaFileInput(capture: Boolean = true): FilePickResult =
    suspendCancellableCoroutine { cont ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/*"
        if (capture) input.setAttribute("capture", "environment")
        input.oncancel = {
            if (cont.isActive) cont.resume(FilePickResult.Cancelled)
        }
        input.onchange = {
            val file = input.files?.item(0)
            if (file == null) {
                if (cont.isActive) cont.resume(FilePickResult.Cancelled)
            } else {
                val reader = FileReader()
                reader.onload = {
                    val bytes = Int8Array(reader.result as ArrayBuffer).toByteArray()
                    if (cont.isActive) cont.resume(FilePickResult.Picked(PickedFile(file.name, file.type, bytes)))
                }
                reader.onerror = {
                    if (cont.isActive) cont.resume(FilePickResult.ReadFailed)
                }
                reader.readAsArrayBuffer(file)
            }
        }
        input.click()
    }
