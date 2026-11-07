package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/** Ktor implementation of [SalesInvoiceApiClient]. */
class KtorSalesInvoiceApiClient(
    private val httpCall: HttpCall,
) : SalesInvoiceApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/sales-invoices"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        status: String?,
        type: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): SalesInvoiceDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                if (!status.isNullOrBlank()) parameter("status", status)
                if (!type.isNullOrBlank()) parameter("type", type)
                if (open) parameter("open", "true")
                if (overdue) parameter("overdue", "true")
                if (debtorId != null) parameter("debtorId", debtorId)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    override suspend fun get(id: Long): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id"))
        }

    override suspend fun createFromBill(request: SalesInvoiceDto.CreateFromBillRequestDto): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/from-bill")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createStandalone(request: SalesInvoiceDto.CreateStandaloneRequestDto): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/standalone")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateStandalone(
        id: Long,
        request: SalesInvoiceDto.UpdateStandaloneRequestDto,
    ): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteStandalone(id: Long) = httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }

    override suspend fun issue(id: Long): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$id/issue"))
        }

    override suspend fun settleAtTill(
        id: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
    ): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$id/settle-at-till")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun cancel(
        id: Long,
        request: SalesInvoiceDto.CancelRequestDto,
    ): SalesInvoiceDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$id/cancel")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun pdf(id: Long): ByteArray =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id/pdf"))
        }

    override suspend fun datevExportStatistics(
        start: String,
        end: String,
    ): DatevArExportDto.ArExportStatisticsDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/export/datev/statistics")) {
                parameter("start", start)
                parameter("end", end)
            }
        }

    override suspend fun datevExport(
        start: String,
        end: String,
    ): ByteArray =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/export/datev")) {
                parameter("start", start)
                parameter("end", end)
            }
        }
}
