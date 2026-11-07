/**
 * Ktor-based implementation of the Rechnungen list API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a
 * `PosApiException` carrying its `PosErrorCode`, so a 403 reaches the caller typed.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorInvoiceListApiClient(
    private val httpCall: HttpCall,
) : InvoiceListApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/invoices"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int,
        offset: Long,
    ): InvoiceListDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("bucket", bucket.name)
                parameter("limit", limit.toString())
                parameter("offset", offset.toString())
            }
        }

    override suspend fun getInvoice(invoiceId: Long): DocumentDto.InvoiceDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$invoiceId"))
        }

    /** The route takes no body — the invoice id in the path is the whole request. */
    override suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$invoiceId/review"))
        }
}
