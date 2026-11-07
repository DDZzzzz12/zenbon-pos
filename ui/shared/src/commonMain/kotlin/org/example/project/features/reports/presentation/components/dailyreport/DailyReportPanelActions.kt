package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto

/** The view-model callbacks a daily-report panel filter reaches, unchanged from the till's filter bar. */
data class DailyReportPanelActions(
    val onEmployeeSelected: (String?) -> Unit,
    val onToggleTrainingMode: (() -> Unit)?,
    val onSortChanged: (StatisticsDto.StatisticsSortOption) -> Unit,
    val onPaymentTypesChanged: (Set<String>) -> Unit,
) {
    /**
     * Sends only what [target] changes against [applied]. Sort first: it reloads only the current
     * list, the others reset and reload everything with it.
     */
    fun apply(
        applied: DailyReportPanelFilter,
        target: DailyReportPanelFilter,
    ) {
        if (target.sort != applied.sort) onSortChanged(target.sort)
        if (target.employeeId != applied.employeeId) onEmployeeSelected(target.employeeId)
        if (target.trainingMode != applied.trainingMode) onToggleTrainingMode?.invoke()
        if (target.paymentTypes != applied.paymentTypes) onPaymentTypesChanged(target.paymentTypes)
    }
}
