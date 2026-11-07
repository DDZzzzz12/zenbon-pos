package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto

/** What the daily report's filter panel edits, held as a draft until its apply button. */
data class DailyReportPanelFilter(
    val employeeId: String?,
    val trainingMode: Boolean,
    val sort: StatisticsDto.StatisticsSortOption,
    val paymentTypes: Set<String>,
) {
    companion object {
        /** The sort a fresh report starts with; any other counts as an applied filter. */
        val DEFAULT_SORT = StatisticsDto.StatisticsSortOption.RECENT
    }
}
