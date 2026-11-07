package org.example.project.core.data.remote

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence

/**
 * Stage C Batch 8: throws-style.
 */
interface MonthlyCloseApiClient {
    suspend fun getMonthlyClose(
        periodStart: String,
        periodEnd: String,
        comparePreviousPeriod: Boolean = true,
        cadence: DatevExportCadence,
    ): MonthlyCloseDto.MonthlyCloseResponseDto
}
