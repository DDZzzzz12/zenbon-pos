/**
 * Ktor-based implementation of VatApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.VatApiClient
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorVatApiClient(
    private val httpCall: HttpCall,
) : VatApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/vat"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllVatRates(): List<VatDto.VatResponseDto> = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun createVatRate(request: VatDto.CreateVatRequestDto): VatDto.VatResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateVatRate(
        vatId: String,
        request: VatDto.UpdateVatRequestDto,
    ): VatDto.VatResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$vatId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteVatRate(vatId: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$vatId")) }
    }
}
