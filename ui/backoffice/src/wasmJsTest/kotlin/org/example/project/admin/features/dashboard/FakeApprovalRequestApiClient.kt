package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.domain.dto.auth.AuthDto

class FakeApprovalRequestApiClient(
    private val pending: () -> List<AuthDto.ApprovalRequestDto>,
) : ApprovalRequestApiClient {
    val approvedIds: MutableList<String> = mutableListOf()
    val deniedIds: MutableList<String> = mutableListOf()
    val deniedReasons: MutableList<String> = mutableListOf()
    var pendingCallCount: Int = 0

    override suspend fun getPendingRequests(): List<AuthDto.ApprovalRequestDto> {
        pendingCallCount++
        return pending()
    }

    override suspend fun approveRequest(requestId: String): AuthDto.ApprovalResponseDto {
        approvedIds.add(requestId)
        return response(requestId, approved = true)
    }

    override suspend fun denyRequest(
        requestId: String,
        reason: String,
    ): AuthDto.ApprovalResponseDto {
        deniedIds.add(requestId)
        deniedReasons.add(reason)
        return response(requestId, approved = false)
    }

    override suspend fun cancelRequest(requestId: String) = throw NotImplementedError()

    override suspend fun getRequestStatus(requestId: String): AuthDto.RequestStatusDto = throw NotImplementedError()

    /** A successful write. Must NOT throw — a throw would send the ViewModel down its error path. */
    private fun response(
        requestId: String,
        approved: Boolean,
    ) = AuthDto.ApprovalResponseDto(
        requestId = requestId,
        approved = approved,
        approvingManagerId = "mgr-1",
        reason = if (approved) null else "test-reason",
    )
}
