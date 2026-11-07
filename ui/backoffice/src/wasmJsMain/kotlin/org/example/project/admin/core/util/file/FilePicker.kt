package org.example.project.admin.core.util.file

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

/**
 * Opens the browser file dialog and resolves to a [FilePickResult] describing what happened:
 * a file was picked and read ([FilePickResult.Picked]), the dialog was dismissed without
 * choosing ([FilePickResult.Cancelled]), or a file was chosen but could not be read
 * ([FilePickResult.ReadFailed]). The coroutine always resumes exactly once.
 *
 * @param accept an HTML input `accept` value, e.g. all image MIME types or "application/pdf".
 */
@OptIn(ExperimentalWasmJsInterop::class)
suspend fun pickFile(accept: String): FilePickResult =
    suspendCancellableCoroutine { cont ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = accept

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
                    val buffer = reader.result as ArrayBuffer
                    val bytes = Int8Array(buffer).toByteArray()
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

/** Convenience wrapper: pick an image file. Null means cancelled or unreadable. */
suspend fun pickImage(): PickedFile? = (pickFile("image/*") as? FilePickResult.Picked)?.file
