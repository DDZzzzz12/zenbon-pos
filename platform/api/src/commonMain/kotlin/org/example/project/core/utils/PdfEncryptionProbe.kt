package org.example.project.core.utils

/**
 * A cheap, library-free guess at whether a PDF is password-protected.
 *
 * Wasm has no PDF library, so the check is a byte scan: an encrypted PDF names its encryption
 * dictionary with `/Encrypt` in the trailer (or in the cross-reference stream's dictionary, which is
 * not compressed). It is a **hint only** — it decides whether to *ask* the operator. Two cases make
 * it imperfect on purpose: a PDF with only an *owner* password carries `/Encrypt` yet opens without a
 * password, and the marker could in theory occur inside content. The server is the authority and
 * settles both when the unlock is attempted.
 */
object PdfEncryptionProbe {
    private val PDF_MAGIC = "%PDF".encodeToByteArray()
    private val ENCRYPT_MARKER = "/Encrypt".encodeToByteArray()

    fun looksEncrypted(bytes: ByteArray): Boolean = startsWith(bytes, PDF_MAGIC) && indexOf(bytes, ENCRYPT_MARKER) >= 0

    private fun startsWith(
        bytes: ByteArray,
        prefix: ByteArray,
    ): Boolean = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        val last = haystack.size - needle.size
        var start = 0
        while (start <= last) {
            if (matchesAt(haystack, needle, start)) return start
            start++
        }
        return -1
    }

    private fun matchesAt(
        haystack: ByteArray,
        needle: ByteArray,
        start: Int,
    ): Boolean = needle.indices.all { haystack[start + it] == needle[it] }
}
