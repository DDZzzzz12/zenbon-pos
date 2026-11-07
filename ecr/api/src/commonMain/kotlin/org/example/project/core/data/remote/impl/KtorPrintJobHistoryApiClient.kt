/**
 * Ktor-based implementation of PrintJobHistoryApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.PrintJobHistoryApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorPrintJobHistoryApiClient(
    private val httpCall: HttpCall,
) : PrintJobHistoryApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/print-job-history"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getPrintJobHistory(
        startDate: String,
        endDate: String,
        limit: Int,
        status: String?,
        printType: String?,
        locationId: Long?,
    ): List<PrinterDto.PrintJobHistoryDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                parameter("limit", limit)
                if (status != null) parameter("status", status)
                if (printType != null) parameter("printType", printType)
                if (locationId != null) parameter("locationId", locationId)
            }
        }
}
