package org.example.project.core.data.remote

import org.example.project.core.domain.dto.changelog.ChangelogDto

/**
 * API client for changelog release notes.
 *
 * Read-only paginated feed over `changelog_release`. Authenticated; every
 * logged-in user may read release notes.
 */
interface ChangelogApiClient {
    /**
     * Fetch a page of release notes, newest first.
     *
     * @param page zero-based page index (default 0)
     * @param size page size, 1..100 (default 20)
     */
    suspend fun list(
        page: Int = 0,
        size: Int = 20,
    ): ChangelogDto.PaginatedChangelogResponseDto
}
