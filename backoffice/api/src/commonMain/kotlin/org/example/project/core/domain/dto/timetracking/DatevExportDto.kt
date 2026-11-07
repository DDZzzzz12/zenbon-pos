package org.example.project.core.domain.dto.timetracking

import kotlinx.serialization.Serializable

object DatevExportDto {
    @Serializable
    data class DatevLineDto(
        val personnelNumber: String,
        val calendarDay: String,
        val absenceCode: String,
        val wageTypeCode: Int,
        val hours: String,
        val days: String,
        val value: String,
        val deviatingFactor: String,
        val deviatingWageChange: String,
        val costCenterCode: String,
        val costCarrier: String,
    )

    @Serializable
    data class DatevPreviewDto(
        val lines: List<DatevLineDto>,
        val warnings: List<String>,
        val totalEmployees: Int,
        val month: Int,
        val year: Int,
        val consultantNumber: String,
        val clientNumber: String,
    )

    @Serializable
    data class ExportResultDto(
        val lineCount: Int,
        val fileName: String,
        val warnings: List<String>,
    )
}
