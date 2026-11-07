package org.example.project.core.presentation.components.docUtil

/**
 * The media type of a document file the till fetched or generated, for [ViewableDocument.mimeType].
 *
 * The till's call sites know only a file name (often a panel title such as "PDF-Vorschau") and the bytes
 * `getDocumentFile` returned, which may be a PDF, an image or an XRechnung XML. The **content** decides
 * first, as the server does on upload (magic bytes, never the client's word); the file extension decides
 * only when the content is not recognized; anything else stays a PDF, so the viewer keeps opening it and
 * reports its own error, exactly as before.
 */
object DocumentMimeType {
    const val PDF = "application/pdf"
    private const val JPEG = "image/jpeg"
    private const val PNG = "image/png"
    private const val XML = "application/xml"

    /** PDF readers accept the `%PDF-` header anywhere in the first KiB. */
    private const val PDF_HEADER_WINDOW = 1024
    private val PDF_HEADER = "%PDF-".encodeToByteArray()
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val XML_DECLARATION = "<?xml".encodeToByteArray()

    private val BY_EXTENSION =
        mapOf(
            "pdf" to PDF,
            "jpg" to JPEG,
            "jpeg" to JPEG,
            "png" to PNG,
            "xml" to XML,
        )

    fun resolve(
        fileName: String,
        bytes: ByteArray,
    ): String = byContent(bytes) ?: byExtension(fileName) ?: PDF

    private fun byContent(bytes: ByteArray): String? =
        when {
            bytes.containsWithin(PDF_HEADER, PDF_HEADER_WINDOW) -> PDF
            bytes.startsWith(JPEG_MAGIC) -> JPEG
            bytes.startsWith(PNG_MAGIC) -> PNG
            bytes.startsWith(XML_DECLARATION) || bytes.startsWith(UTF8_BOM + XML_DECLARATION) -> XML
            else -> null
        }

    private fun byExtension(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return BY_EXTENSION[extension.lowercase()]
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = startsAt(prefix, 0)

    private fun ByteArray.containsWithin(
        needle: ByteArray,
        window: Int,
    ): Boolean {
        val lastStart = minOf(size, window) - needle.size
        return (0..lastStart).any { startsAt(needle, it) }
    }

    private fun ByteArray.startsAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean {
        if (offset < 0 || offset + prefix.size > size) return false
        return prefix.indices.all { this[offset + it] == prefix[it] }
    }
}
