package org.example.project.core.domain.dto.datevexportlog

import kotlinx.serialization.Serializable

@Serializable
data class CatchupPeriodDto(
    val periodStart: String,
    val periodEnd: String,
    val periodLabel: String,
)

@Serializable
data class CatchupPreviewDto(
    val periods: List<CatchupPeriodDto>,
)

@Serializable
data class CatchupFailureDto(
    val periodLabel: String,
    val periodStart: String,
    val periodEnd: String,
    val error: String,
)

@Serializable
data class CatchupResultDto(
    val exported: List<DatevExportLogDto>,
    val failed: List<CatchupFailureDto>,
    /**
     * Periods not attempted because an earlier one failed. A catch-up stops at the first failed
     * period: the export log's cursor is its latest `periodEnd`, so exporting a later period past a
     * failed one would move the cursor over the gap and the failed period would never be retried.
     * These run again on the next catch-up, after the failed one. Defaults to empty for an older server.
     */
    val deferred: List<CatchupPeriodDto> = emptyList(),
)
