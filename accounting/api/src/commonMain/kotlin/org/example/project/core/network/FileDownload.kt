package org.example.project.core.network

import org.example.project.core.domain.dto.datev.UnassignedSummary

/**
 * A downloaded file body plus the metadata the server sent with it.
 *
 * [fileName] comes from the Content-Disposition header, [contentType] from the
 * Content-Type header; both are null when the server did not send the header,
 * in which case callers fall back to their own defaults. [warnings] are the notices the export
 * produced, carried by [ExportWarningHeaders]; empty when there were none. [unassignedContra] names
 * the lines a DATEV export left without a contra account ([ExportWarningHeaders.UNASSIGNED]).
 */
data class FileDownload(
    val bytes: ByteArray,
    val fileName: String? = null,
    val contentType: String? = null,
    val warnings: List<String> = emptyList(),
    val unassignedContra: UnassignedSummary? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDownload) return false
        return bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            warnings == other.warnings &&
            unassignedContra == other.unassignedContra
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + warnings.hashCode()
        result = 31 * result + (unassignedContra?.hashCode() ?: 0)
        return result
    }
}
