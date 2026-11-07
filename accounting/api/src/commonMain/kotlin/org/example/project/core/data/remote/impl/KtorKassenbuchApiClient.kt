/**
 * Ktor implementation of KassenbuchApiClient.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.KassenbuchCreateOutcome
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw
import org.example.project.core.utils.normalizeServerFormattedAmount

class KtorKassenbuchApiClient(
    private val httpCall: HttpCall,
) : KassenbuchApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/kassenbuch"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getCurrentBalance(cashBookId: Long?): String =
        normalizeServerFormattedAmount(
            httpCall.httpClient.apiCall<String> {
                get(url("$BASE_ENDPOINT/balance")) {
                    if (cashBookId != null) parameter("cashBookId", cashBookId)
                }
            },
        )

    override suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean,
        cashBookId: Long?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                if (unlinkedOnly) parameter("unlinkedOnly", "true")
                if (cashBookId != null) parameter("cashBookId", cashBookId)
            }
        }

    override suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries/search")) {
                parameter("q", query)
                // One comma-separated parameter, not one parameter per id: the route reads
                // queryParameters["cashBookIds"] (first occurrence only) and splits it on ",".
                if (!cashBookIds.isNullOrEmpty()) parameter("cashBookIds", cashBookIds.joinToString(","))
            }
        }

    override suspend fun getRecentEntries(limit: Int): List<KassenbuchDto.KassenbuchGroupedSummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries/recent")) {
                parameter("limit", limit)
            }
        }

    override suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries/$entryId"))
        }

    override suspend fun getCurrentBalanceRaw(): String =
        normalizeServerFormattedAmount(httpCall.httpClient.apiCall<String> { get(url("$BASE_ENDPOINT/balance?raw=true")) })

    override suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/cash-count/expected")) }

    override suspend fun getAvailableCash(cashBookId: Long?): KassenbuchDto.AvailableCashDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/available-cash")) {
                if (cashBookId != null) parameter("cashBookId", cashBookId)
            }
        }

    override suspend fun recordCashCount(request: KassenbuchDto.CashCountRequestDto): KassenbuchDto.CashCountResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/cash-count")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createEntry(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): KassenbuchCreateOutcome {
        val response =
            httpCall.httpClient.apiCallRaw {
                post(url(BASE_ENDPOINT)) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
        // 202: the body is the approval request's acknowledgement, not an entry — nothing is booked.
        return if (response.status == HttpStatusCode.Accepted) {
            KassenbuchCreateOutcome.AwaitingApproval
        } else {
            KassenbuchCreateOutcome.Created(response.body())
        }
    }

    override suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries/grouped/$headerId"))
        }

    override suspend fun exportToDatevXml(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/export/datev-xml")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                if (periodLabel.isNotBlank()) parameter("periodLabel", periodLabel)
            }
        }

    override suspend fun exportToBuchungsstapel(
        startDate: String,
        endDate: String,
        periodLabel: String,
    ): DatevExportLogDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/export/buchungsstapel")) {
                parameter("startDate", startDate)
                parameter("endDate", endDate)
                if (periodLabel.isNotBlank()) parameter("periodLabel", periodLabel)
            }
        }

    override suspend fun linkDocument(
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/entries/$headerId/link-document")) {
                contentType(ContentType.Application.Json)
                setBody(KassenbuchDto.LinkDocumentRequestDto(documentId = documentId))
            }
        }

    override suspend fun unlinkDocument(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto =
        httpCall.httpClient.apiCall {
            delete(url("$BASE_ENDPOINT/entries/$headerId/link-document"))
        }

    override suspend fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/entries/$headerId/replace-document")) {
                contentType(ContentType.Application.Json)
                setBody(KassenbuchDto.ReplaceDocumentRequestDto(newDocumentId = newDocumentId))
            }
        }

    override suspend fun correctEntry(
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/entries/$headerId/correct")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
