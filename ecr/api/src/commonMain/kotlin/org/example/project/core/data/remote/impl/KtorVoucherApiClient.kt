/**
 * Ktor implementation of VoucherApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorVoucherApiClient(
    private val httpCall: HttpCall,
) : VoucherApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/vouchers"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun createVoucher(request: VoucherDto.CreateVoucherRequestDto): VoucherDto.VoucherResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): VoucherDto.ValidateVoucherResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/validate")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ): List<VoucherDto.VoucherResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/created-by-date")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                employeeId?.let { parameter("employeeId", it) }
            }
        }

    override suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ): List<VoucherDto.VoucherRedemptionSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/redeemed-by-date")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                employeeId?.let { parameter("employeeId", it) }
            }
        }

    override suspend fun getVoucherStatistics(): VoucherDto.VoucherStatisticsDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/statistics")) }

    override suspend fun getActiveVouchers(
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/active")) {
                limit?.let { parameter("limit", it) }
                parameter("offset", offset)
            }
        }

    override suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/search")) {
                parameter("q", searchTerm)
                limit?.let { parameter("limit", it) }
                parameter("offset", offset)
            }
        }

    override suspend fun getRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/number/$voucherNumber/redemption-history"))
        }
}
