package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Stage C Batch 8: throws-style.
 */
class KtorZBonApiClient(
    private val httpCall: HttpCall,
) : ZBonApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/z-bon"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getLatestZBonEndTime(): String? = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/latest/datetime")) }

    override suspend fun createZBon(
        countedAmount: String?,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto>,
        topUp: ZBonDto.ClosingTopUpRequestDto?,
    ): ZBonDto.ZBonResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                // No body at all when there is nothing to send — what an older till sent, too.
                if (countedAmount != null || tipPayouts.isNotEmpty() || topUp != null) {
                    contentType(ContentType.Application.Json)
                    setBody(ZBonDto.CreateZBonRequestDto(countedAmount = countedAmount, tipPayouts = tipPayouts, topUp = topUp))
                }
            }
        }

    override suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/open-period-tips")) }

    override suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/recent")) {
                parameter("limit", limit)
            }
        }

    override suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/history")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
            }
        }

    override suspend fun getById(zbonId: Long): ZBonDto.ZBonResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$zbonId")) }

    override suspend fun printZBon(zbonId: Long) {
        httpCall.httpClient.apiCall<Unit> { post(url("$BASE_ENDPOINT/$zbonId/print")) }
    }

    override suspend fun downloadZBonPdf(zbonId: Long): ByteArray = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$zbonId/pdf")) }

    override suspend fun payOutTip(
        zbonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$zbonId/tip-payouts")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun reverseTipPayout(
        zbonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$zbonId/tip-payouts/$payoutHeaderId/reverse")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun reverseTipSettlement(
        zbonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$zbonId/tip-payouts/settlements/$payoutId/reverse")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getClosingReadiness(): ZBonDto.ClosingReadinessDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/closing-readiness")) }
}
