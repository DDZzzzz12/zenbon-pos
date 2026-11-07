package org.example.project.admin.features.reports

import org.example.project.core.data.remote.MonthlyCloseApiClient
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence

/** Records the window and cadence it was asked for, and replays [response] or throws [failWith]. */
internal class FakeMonthlyCloseApiClient(
    var response: MonthlyCloseDto.MonthlyCloseResponseDto = monthlyClose(),
    var failWith: Throwable? = null,
) : MonthlyCloseApiClient {
    var lastPeriodStart: String? = null
    var lastPeriodEnd: String? = null
    var lastCadence: DatevExportCadence? = null
    var callCount = 0

    override suspend fun getMonthlyClose(
        periodStart: String,
        periodEnd: String,
        comparePreviousPeriod: Boolean,
        cadence: DatevExportCadence,
    ): MonthlyCloseDto.MonthlyCloseResponseDto {
        callCount++
        lastPeriodStart = periodStart
        lastPeriodEnd = periodEnd
        lastCadence = cadence
        failWith?.let { throw it }
        return response
    }
}
