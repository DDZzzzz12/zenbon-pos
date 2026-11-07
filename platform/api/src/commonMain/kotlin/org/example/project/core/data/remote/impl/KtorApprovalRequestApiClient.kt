package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor HTTP implementation of [ApprovalRequestApiClient].
 *
 * Throws-style: raises [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 */
class KtorApprovalRequestApiClient(
    private val httpCall: HttpCall,
) : ApprovalRequestApiClient {
    companion object {
        private const val BASE = "api/auth/approvals"
        private const val PENDING = "$BASE/pending"

        private fun approve(id: String) = "$BASE/$id/approve"

        private fun deny(id: String) = "$BASE/$id/deny"

        private fun status(id: String) = "$BASE/$id/status"

        private fun cancel(id: String) = "$BASE/$id"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> = httpCall.httpClient.apiCall { get(url(PENDING)) }

    override suspend fun approveRequest(requestId: String): AuthDto.ApprovalResponseDto = httpCall.httpClient.apiCall { post(url(approve(requestId))) }

    override suspend fun denyRequest(
        requestId: String,
        reason: String,
    ): AuthDto.ApprovalResponseDto =
        httpCall.httpClient.apiCall {
            post(url(deny(requestId))) {
                contentType(ContentType.Application.Json)
                setBody(mapOf("reason" to reason))
            }
        }

    override suspend fun cancelRequest(requestId: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url(cancel(requestId))) }
    }

    override suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto = httpCall.httpClient.apiCall { get(url(status(requestId))) }
}
