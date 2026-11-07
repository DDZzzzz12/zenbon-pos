package org.example.project.admin.features.zBon.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * UI state for the Z-Bon history list screen: the reporting period (granularity + anchor) being
 * viewed, the loading/error flags, and the fetched [ZBonDto.ZBonSummeryDto] rows.
 */
data class ZBonListState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val granularity: ReportsGranularity = ReportsGranularity.MONTH,
    /** The date identifying the period shown; the ViewModel seeds it with today. */
    val anchor: LocalDate,
    val zBons: List<ZBonDto.ZBonSummeryDto> = emptyList(),
)
