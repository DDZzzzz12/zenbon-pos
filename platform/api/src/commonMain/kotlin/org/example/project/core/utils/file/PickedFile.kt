package org.example.project.core.utils.file

/**
 * A file chosen by the user via a platform file dialog. [bytes] are the raw file contents;
 * [fileName] and [mimeType] come from whatever the platform reports (on wasm, the browser
 * `File` object).
 *
 * A plain data class (not @JvmInline value class — unavailable on wasm).
 */
data class PickedFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)
