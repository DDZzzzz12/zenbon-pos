package org.example.project.features.kassenbuch.domain.service

import org.example.project.core.domain.dto.datev.UnassignedSummary

/**
 * Holds the in-memory result of a transient DATEV export — one that skips
 * disk persistence and log-record creation.
 *
 * Used by [DatevFileExportService.exportTransient] to return the raw file bytes
 * and a suggested filename to the caller (typically a route handler that streams
 * the bytes directly to the client).
 */
data class TransientExportResult(
    val bytes: ByteArray,
    val fileName: String,
    /**
     * Informational notices about the export — e.g. a Geldtransit whose counterpart leg was
     * excluded by a partial cash-book selection. Non-fatal: the export still completes.
     * Defaulted so no existing caller needs to change.
     */
    val warnings: List<String> = emptyList(),
    /**
     * The lines written without a contra account because their mapping cell's „Konto automatisch
     * zuordnen" switch is off; null when there were none. Defaulted like [warnings].
     */
    val unassignedContra: UnassignedSummary? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransientExportResult) return false
        return fileName == other.fileName &&
            bytes.contentEquals(other.bytes) &&
            warnings == other.warnings &&
            unassignedContra == other.unassignedContra
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + warnings.hashCode()
        result = 31 * result + (unassignedContra?.hashCode() ?: 0)
        return result
    }
}
