/**
 * API client for TSE signing queue operations.
 *
 * Stage C Batch 8: throws-style.
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
import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * HTTP client for TSE queue management API endpoints.
 *
 * All endpoints require JWT authentication.
 */
class KtorTseQueueApiClient(
    private val httpCall: HttpCall,
) : TseQueueApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/tse/queue"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/status")) }

    override suspend fun getQueueEntries(
        status: TseSigningStatus?,
        limit: Int,
        offset: Int,
    ): List<TseSigningQueueDto.QueueEntryResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/entries")) {
                status?.let { parameter("status", it.name) }
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    override suspend fun retryEntry(entryId: Long): TseSigningQueueDto.EntryOperationResponseDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/entries/$entryId/retry")) }

    override suspend fun cancelEntry(
        entryId: Long,
        reason: String,
    ): TseSigningQueueDto.EntryOperationResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/entries/$entryId/cancel")) {
                contentType(ContentType.Application.Json)
                setBody(TseSigningQueueDto.CancelEntryRequestDto(reason))
            }
        }

    override suspend fun pauseService(): TseSigningQueueDto.ServiceControlResponseDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/pause")) }

    override suspend fun resumeService(): TseSigningQueueDto.ServiceControlResponseDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/resume")) }

    override suspend fun triggerRetry(): TseSigningQueueDto.TriggerRetryResponseDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/trigger")) }

    override suspend fun cleanupOldEntries(olderThanDays: Int): TseSigningQueueDto.CleanupResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/cleanup")) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("olderThanDays" to olderThanDays))
            }
        }
}
