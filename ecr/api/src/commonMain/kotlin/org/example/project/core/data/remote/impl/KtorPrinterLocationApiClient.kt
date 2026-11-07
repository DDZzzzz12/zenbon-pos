/**
 * Ktor-based implementation of PrinterLocationApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.PrinterLocationApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorPrinterLocationApiClient(
    private val httpCall: HttpCall,
) : PrinterLocationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/printer-location"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllPrinterLocations(includeInactive: Boolean): List<PrinterDto.PrinterLocationResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("includeInactive", includeInactive)
            }
        }

    override suspend fun createPrinterLocation(request: PrinterDto.CreatePrinterLocationRequestDto): PrinterDto.PrinterLocationResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updatePrinterLocation(
        id: Long,
        request: PrinterDto.UpdatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deactivatePrinterLocation(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun activatePrinterLocation(id: Long) {
        httpCall.httpClient.apiCall<Unit> { patch(url("$BASE_ENDPOINT/$id/activate")) }
    }
}
