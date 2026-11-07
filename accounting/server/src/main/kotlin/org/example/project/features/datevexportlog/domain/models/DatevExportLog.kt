package org.example.project.features.datevexportlog.domain.models

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setting.DatevExportSource
import java.util.UUID

data class DatevExportLog(
    val id: Long,
    val exportType: DatevExportType,
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val exportedAt: LocalDateTime,
    val exportedByUserId: UUID,
    val fileHash: String,
    val fileName: String,
    val rowCount: Int,
    val entryCount: Int,
    val periodFolder: String,
    val source: DatevExportSource,
)
