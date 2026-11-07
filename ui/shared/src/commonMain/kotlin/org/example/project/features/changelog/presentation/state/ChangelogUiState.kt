package org.example.project.features.changelog.presentation.state

import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.core.presentation.error.UiError

/**
 * UI state for the Changelog ("What's new") screen.
 *
 * Holds the paginated list of release-note entries plus loading/error flags.
 * The full page response is retained so pagination metadata (hasNext, currentPage)
 * survives across appended pages.
 *
 * @property items Release-note entries loaded so far
 * @property pagination Most recent page response (items + pagination metadata)
 * @property isLoading Whether the first page is currently loading
 * @property isLoadingMore Whether a subsequent page is currently loading
 * @property error The last UI error, if any
 */
data class ChangelogUiState(
    val items: List<ChangelogDto.ResponseDto> = emptyList(),
    val pagination: ChangelogDto.PaginatedChangelogResponseDto? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiError? = null,
)
