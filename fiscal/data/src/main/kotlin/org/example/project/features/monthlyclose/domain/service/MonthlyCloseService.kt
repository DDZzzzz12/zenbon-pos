package org.example.project.features.monthlyclose.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence

interface MonthlyCloseService {
    /**
     * `periodStart` / `periodEnd` are in the shop's local timezone (the same
     * zone every persisted Z-Bon / bill / Kassenbuch entry is in). Half-open
     * interval `[periodStart, periodEnd)`.
     */
    suspend fun getMonthlyClose(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        employeeId: String,
        comparePreviousPeriod: Boolean,
        cadence: DatevExportCadence,
    ): MonthlyCloseDto.MonthlyCloseResponseDto
}
