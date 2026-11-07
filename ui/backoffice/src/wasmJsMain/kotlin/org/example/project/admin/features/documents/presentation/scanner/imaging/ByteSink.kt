package org.example.project.admin.features.documents.presentation.scanner.imaging

/** Growable byte buffer; wasm has no `ByteArrayOutputStream`. */
internal class ByteSink {
    private var buffer = ByteArray(64 * 1024)
    var size = 0
        private set

    fun ascii(s: String) = bytes(s.encodeToByteArray())

    fun bytes(b: ByteArray) {
        if (size + b.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + b.size))
        b.copyInto(buffer, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
