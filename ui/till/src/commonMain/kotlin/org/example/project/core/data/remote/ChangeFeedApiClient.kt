package org.example.project.core.data.remote

import org.example.project.core.domain.dto.sync.ChangeFeedDto

interface ChangeFeedApiClient {
    suspend fun getChanges(
        since: Long,
        limit: Int = 500,
    ): ChangeFeedDto.ResponseDto
}
