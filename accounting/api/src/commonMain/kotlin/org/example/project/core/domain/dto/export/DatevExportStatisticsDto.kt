package org.example.project.core.domain.dto.export

import kotlinx.serialization.Serializable

/**
 * Pre-flight statistics for a DATEV export, shown in the UI before
 * the user commits to downloading.
 *
 * Supersedes the format-specific ExportStatisticsDto variants.
 */
@Serializable
data class DatevExportStatisticsDto(
    val totalEntries: Int,
    val totalDays: Int,
    val totalAmountFormatted: String,
    val dateRange: String,
)
