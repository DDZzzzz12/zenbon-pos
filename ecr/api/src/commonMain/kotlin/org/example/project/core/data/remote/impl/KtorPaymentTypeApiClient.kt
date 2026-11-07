/**
 * Ktor-based implementation of PaymentTypeApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorPaymentTypeApiClient(
    private val httpCall: HttpCall,
) : PaymentTypeApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/payment-types"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllPaymentTypes(includeInactive: Boolean): PaymentTypeDto.PaymentTypeListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("includeInactive", includeInactive)
            }
        }

    override suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/active")) }

    override suspend fun createPaymentType(request: PaymentTypeDto.CreatePaymentTypeRequestDto): PaymentTypeDto.PaymentTypeResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updatePaymentType(
        id: Long,
        request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deletePaymentType(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/statistics")) }

    override suspend fun syncPaymentTypes(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }
}
