/**
 * Ktor implementation of BillApiClient.
 *
 * Stage C Batch 7: throws-style. Uses [apiCall] which raises
 * [org.example.project.core.network.error.PosApiException] (server returned
 * ProblemDetail) or [org.example.project.core.network.error.NetworkApiException]
 * (transport failure) on non-2xx / IO errors.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorBillApiClient(
    private val httpCall: HttpCall,
) : BillApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/bills"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getBillById(billId: Long): BillDto.BillResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$billId")) }

    override suspend fun getBillByNumber(billNumber: String): BillDto.BillResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/number/$billNumber")) }

    override suspend fun getBillByTseTransactionNumber(transactionNumber: Long): BillDto.BillResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/tse-transaction/$transactionNumber")) }

    override suspend fun createBill(request: BillDto.CreateBillRequestDto): BillDto.BillResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/table-session/$tableSessionId")) {
                // The server defaults to active bills only; a receipt list needs Stornos and the
                // cancelled originals too (admin17 FAIL-2: this flag was accepted and never sent).
                parameter("includeDeleted", includeDeleted)
            }
        }

    override suspend fun deleteBill(
        billId: Long,
        deletingReason: String?,
        restoreSession: Boolean,
    ): String =
        httpCall.httpClient.apiCall {
            delete(url("$BASE_ENDPOINT/$billId")) {
                deletingReason?.let { parameter("reason", it) }
                parameter("restoreSession", restoreSession)
            }
        }

    override suspend fun rebookBill(
        billId: Long,
        request: BillDto.RebookRequestDto,
    ): BillDto.BillResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$billId/rebook")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun printBill(
        billId: Long,
        bewirtungsbeleg: Boolean,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            post(url("$BASE_ENDPOINT/$billId/print")) {
                if (bewirtungsbeleg) parameter("bewirtungsbeleg", "true")
            }
        }
    }

    override suspend fun getAllBills(includeDeleted: Boolean): List<BillDto.BillResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("includeDeleted", includeDeleted)
            }
        }

    override suspend fun search(
        employeeNumber: String?,
        tableSessionId: Long?,
        startDate: String?,
        endDate: String?,
        minAmount: Double?,
        maxAmount: Double?,
        tipRecipient: String?,
        includeDeleted: Boolean,
        offset: Int,
        limit: Int,
        includeTraining: Boolean,
    ): List<BillDto.BillResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/search")) {
                employeeNumber?.let { parameter("employeeNumber", it) }
                tableSessionId?.let { parameter("tableSessionId", it) }
                startDate?.let { parameter("startDate", it) }
                endDate?.let { parameter("endDate", it) }
                minAmount?.let { parameter("minAmount", it) }
                maxAmount?.let { parameter("maxAmount", it) }
                tipRecipient?.let { parameter("tipRecipient", it) }
                parameter("includeDeleted", includeDeleted)
                if (includeTraining) parameter("includeTraining", true)
                parameter("offset", offset)
                parameter("limit", limit)
            }
        }

    override suspend fun getSummaries(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean,
    ): List<BillDto.BillSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/summaries")) {
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("includeDeleted", includeDeleted)
            }
        }

    override suspend fun getStatistics(
        startDate: String?,
        endDate: String?,
        includeDeleted: Boolean,
    ): BillDto.BillStatisticsDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/statistics")) {
                startDate?.let { parameter("startDate", it) }
                endDate?.let { parameter("endDate", it) }
                parameter("includeDeleted", includeDeleted)
            }
        }
}
