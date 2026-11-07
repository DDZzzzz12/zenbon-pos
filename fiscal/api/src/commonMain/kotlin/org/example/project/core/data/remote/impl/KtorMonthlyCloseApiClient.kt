package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.MonthlyCloseApiClient
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Stage C Batch 8: throws-style.
 */
class KtorMonthlyCloseApiClient(
    private val httpCall: HttpCall,
) : MonthlyCloseApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/monthly-close"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getMonthlyClose(
        periodStart: String,
        periodEnd: String,
        comparePreviousPeriod: Boolean,
        cadence: DatevExportCadence,
    ): MonthlyCloseDto.MonthlyCloseResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("periodStart", periodStart)
                parameter("periodEnd", periodEnd)
                parameter("comparePreviousPeriod", comparePreviousPeriod)
                parameter("cadence", cadence.name)
            }
        }
}
