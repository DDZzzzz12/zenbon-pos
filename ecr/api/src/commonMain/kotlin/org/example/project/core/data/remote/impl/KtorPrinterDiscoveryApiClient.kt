/**
 * Ktor-based implementation of PrinterDiscoveryApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import org.example.project.core.data.remote.PrinterDiscoveryApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorPrinterDiscoveryApiClient(
    private val httpCall: HttpCall,
) : PrinterDiscoveryApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/printer-discovery"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun discoverPrinters(): List<PrinterDto.DiscoveredPrinterDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/discover")) }

    override suspend fun getPrinterStatus(uri: String): PrinterDto.DiscoveredPrinterDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/status")) { parameter("uri", uri) }
        }

    override suspend fun getSupportedFormats(uri: String): List<String> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/formats")) { parameter("uri", uri) }
        }

    override suspend fun printPdf(
        pdfBytes: ByteArray,
        printerLocationId: Long,
    ): PrinterDto.PrintResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/print-pdf")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("printerLocationId", printerLocationId.toString())
                            append(
                                "file",
                                pdfBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
                                    append(HttpHeaders.ContentType, "application/pdf")
                                },
                            )
                        },
                    ),
                )
            }
        }

    override suspend fun testPrint(printerLocationId: Long): PrinterDto.PrintResponseDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/test-print/$printerLocationId")) }
}
