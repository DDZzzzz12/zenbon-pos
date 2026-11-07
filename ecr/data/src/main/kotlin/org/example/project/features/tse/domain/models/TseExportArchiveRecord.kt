package org.example.project.features.tse.domain.models

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin

/** One row of the TSE archive register (`tse_export_archive`); [id] is 0 for a row not yet stored. */
data class TseExportArchiveRecord(
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val tseSerialHex: String,
    val tanrFrom: Long?,
    val tanrTo: Long?,
    val signatureCounterFrom: Long?,
    val signatureCounterTo: Long?,
    val logTimeFrom: Long?,
    val logTimeTo: Long?,
    val sizeBytes: Long,
    val sha256: String,
    val origin: TseExportArchiveOrigin,
    val createdAt: LocalDateTime,
    val createdByEmployeeId: String?,
)
