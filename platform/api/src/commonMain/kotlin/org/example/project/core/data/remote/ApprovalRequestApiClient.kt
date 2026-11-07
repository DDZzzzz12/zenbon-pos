package org.example.project.core.data.remote

import org.example.project.core.domain.dto.auth.AuthDto

/**
 * API client for approval request operations.
 *
 * Provides methods for managers to view, approve, and deny
 * real-time approval requests from cashiers.
 *
 * Stage C migration: throws [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 */
interface ApprovalRequestApiClient {
    suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto>

    suspend fun approveRequest(requestId: String): AuthDto.ApprovalResponseDto

    suspend fun denyRequest(
        requestId: String,
        reason: String,
    ): AuthDto.ApprovalResponseDto

    suspend fun cancelRequest(requestId: String)

    suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto
}
