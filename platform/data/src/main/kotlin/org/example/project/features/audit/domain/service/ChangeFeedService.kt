package org.example.project.features.audit.domain.service

import org.example.project.core.domain.dto.sync.ChangeFeedDto

interface ChangeFeedService {
    /**
     * Ordered client-synced changes with seq > [sinceSeq], plus current water marks.
     *
     * [limit] is capped internally (to 1000); callers may pass any positive value.
     * In the returned response, `highWaterSeq` is the newest client-visible seq
     * (client-synced, non-ERROR rows only — NOT the raw table max), and
     * `minAvailableSeq` is the oldest retained seq across the whole table (clients
     * whose cursor is below it have lost history and must full-sync).
     */
    suspend fun getChanges(
        sinceSeq: Long,
        limit: Int,
    ): ChangeFeedDto.ResponseDto
}
