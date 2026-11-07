/**
 * Ktor-based implementation of RTableApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.RTableApiClient
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorRTableApiClient(
    private val httpCall: HttpCall,
) : RTableApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/tables"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllTables(
        status: String?,
        locationTypeId: Long?,
        limit: Int?,
        offset: Int?,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                status?.let { parameter("status", it) }
                locationTypeId?.let { parameter("locationTypeId", it) }
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
                if (includeInactive) parameter("includeInactive", true)
            }
        }

    override suspend fun searchTables(
        query: String,
        limit: Int,
        includeInactive: Boolean,
    ): RTableDto.RTableListResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/search")) {
                parameter("q", query)
                parameter("limit", limit)
                if (includeInactive) parameter("includeInactive", true)
            }
        }

    override suspend fun getTableById(id: Long): RTableDto.RTableResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createTable(request: RTableDto.CreateRTableRequestDto): RTableDto.RTableResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateTable(
        id: Long,
        request: RTableDto.UpdateRTableRequestDto,
    ): RTableDto.RTableResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun batchUpdatePositions(request: RTableDto.BatchTablePositionUpdateDto): RTableDto.BatchUpdatePositionsResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/batch-update-positions")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteTable(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun syncTables(lastSyncTime: String): List<RTableDto.RTableResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun getCurrentSession(
        tableId: Long,
        forEmployeeNumber: String?,
    ): TableSessionDto.TableSessionResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$tableId/currentSession")) {
                forEmployeeNumber?.let { parameter("forEmployeeNumber", it) }
            }
        }

    override suspend fun transferTable(
        sourceTableId: Long,
        request: RTableDto.TransferTableRequestDto,
    ): RTableDto.TransferTableResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$sourceTableId/transfer")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteTableSession(
        tableId: Long,
        reason: String,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            delete(url("$BASE_ENDPOINT/$tableId/session")) { parameter("reason", reason) }
        }
    }

    override suspend fun getCustomerAndAddressBySessionId(sessionId: Long): TableSessionDto.SessionCustomerDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sessions/$sessionId/customer"))
        }

    override suspend fun assignCustomerAndAddress(
        sessionId: Long,
        request: TableSessionDto.AssignCustomerAndAddressRequestDto,
    ): TableSessionDto.TableSessionResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/sessions/$sessionId/assign-customer")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun removeCustomerAndAddress(sessionId: Long): TableSessionDto.TableSessionResponseDto =
        httpCall.httpClient.apiCall {
            delete(url("$BASE_ENDPOINT/sessions/$sessionId/remove-customer"))
        }

    override suspend fun requestBilling(tableId: Long) {
        httpCall.httpClient.apiCall<Unit> {
            post(url("$BASE_ENDPOINT/$tableId/request-billing"))
        }
    }

    override suspend fun uploadTablesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/import-csv/$overwrite")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                csvContent,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, "text/csv")
                                },
                            )
                        },
                    ),
                )
            }
        }

    override suspend fun getCombinablePartners(tableId: Long): List<Long> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$tableId/combinable")) }

    override suspend fun setCombinablePartners(
        tableId: Long,
        partnerIds: List<Long>,
    ): List<Long> =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$tableId/combinable")) {
                contentType(ContentType.Application.Json)
                setBody(partnerIds)
            }
        }

    override suspend fun getAllCombinablePairs(): List<RTableDto.CombinablePairDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/combinable")) }

    override suspend fun setAllCombinablePairs(pairs: List<RTableDto.CombinablePairDto>): List<RTableDto.CombinablePairDto> =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/combinable")) {
                contentType(ContentType.Application.Json)
                setBody(RTableDto.SetCombinablePairsRequestDto(pairs))
            }
        }
}
