package org.example.project.features.changelog.domain.service

import org.example.project.core.domain.dto.changelog.ChangelogDto

/** Business operations for release notes. Read-only; writes happen via Flyway migrations. */
interface ChangelogService {
    /** Page is 0-indexed. Size is bounded [1..100]. */
    suspend fun list(
        page: Int,
        size: Int,
    ): ChangelogDto.PaginatedChangelogResponseDto
}
