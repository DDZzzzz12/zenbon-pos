package org.example.project.core.domain.dto.datevexportlog

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.datev.UnassignedSummary

@Serializable
data class DatevExportLogDto(
    val id: Long,
    val exportType: String, // KASSENBUCH | AP_LEDGER | BUCHUNGSSTAPEL
    val periodStart: String, // ISO LocalDateTime (shop timezone)
    val periodEnd: String,
    val exportedAt: String,
    val exportedByUserId: String,
    val fileHash: String,
    val fileName: String,
    val rowCount: Int,
    val entryCount: Int,
    val periodFolder: String = "",
    /** AUTO = scheduled job; MANUAL = ad-hoc user export. Only AUTO rows count toward catchup coverage. */
    val source: String = "MANUAL", // AUTO | MANUAL
    /**
     * Informational notices about the export — e.g. a Geldtransit whose counterpart leg was
     * excluded by a partial cash-book selection. Non-fatal: the export still completes.
     * Defaulted so no existing caller/serialized payload needs to change.
     */
    val warnings: List<String> = emptyList(),
    /**
     * The lines this export left without a contra account because their mapping cell's „Konto
     * automatisch zuordnen" switch is off; null when there were none. Not persisted in the log —
     * like [warnings], it describes the run that produced this DTO.
     */
    val unassignedContra: UnassignedSummary? = null,
)
