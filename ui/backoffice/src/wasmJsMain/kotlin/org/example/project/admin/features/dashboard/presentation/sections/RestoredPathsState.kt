package org.example.project.admin.features.dashboard.presentation.sections

import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.presentation.error.UiError

/**
 * The post-restore path review as the dashboard shows it.
 *
 * @property paths restored folder paths of another installation still awaiting a decision; the
 *   notice is shown only while this is non-empty
 * @property deciding the setting whose decision is in flight — its buttons are disabled meanwhile
 * @property justResolved the last decision cleared the review; a one-line confirmation replaces the
 *   notice until the next refresh
 */
data class RestoredPathsState(
    val isLoading: Boolean = true,
    val paths: List<RestoredPathsDto.PathDto> = emptyList(),
    val deciding: RestoredPathSetting? = null,
    val justResolved: Boolean = false,
    val error: UiError? = null,
)
