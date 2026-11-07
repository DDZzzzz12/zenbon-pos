/**
 * Result of a DATEV export operation — bytes + filename + MIME type.
 *
 * Used by all DATEV export services so route handlers can emit Content-Disposition
 * and Content-Type uniformly without format-specific branching.
 */
package org.example.project.core.domain.dto.export

data class DatevExportResult(
    val bytes: ByteArray,
    val filename: String,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DatevExportResult) return false
        return bytes.contentEquals(other.bytes) &&
            filename == other.filename &&
            contentType == other.contentType
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}
