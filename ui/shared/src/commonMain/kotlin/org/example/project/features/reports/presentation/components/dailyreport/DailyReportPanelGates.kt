package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.features.reports.presentation.state.DashboardViewMode

/**
 * Which daily-report panel fields the operator is offered — the till's gates: the employee only with
 * the shop-wide reporting capability ([canReportShopWide]), sort not on the Tische view
 * ([sortShown]), training only where the toggle exists ([trainingShown]). Payment types are always
 * a panel field (shown when the period has any).
 */
data class DailyReportPanelGates(
    val canReportShopWide: Boolean,
    val sortShown: Boolean,
    val trainingShown: Boolean,
) {
    /** What is really applied and visible: a value the operator cannot see or change is not counted. */
    fun effective(applied: DailyReportPanelFilter): DailyReportPanelFilter =
        DailyReportPanelFilter(
            employeeId = applied.employeeId.takeIf { canReportShopWide },
            trainingMode = applied.trainingMode && trainingShown,
            sort = if (sortShown) applied.sort else DailyReportPanelFilter.DEFAULT_SORT,
            paymentTypes = applied.paymentTypes,
        )

    /** [applied] with every offered panel field reset; fields the panel does not offer are kept. */
    fun cleared(applied: DailyReportPanelFilter): DailyReportPanelFilter =
        applied.copy(
            employeeId = if (canReportShopWide) null else applied.employeeId,
            trainingMode = if (trainingShown) false else applied.trainingMode,
            sort = if (sortShown) DailyReportPanelFilter.DEFAULT_SORT else applied.sort,
            paymentTypes = emptySet(),
        )

    companion object {
        fun of(
            canReportShopWide: Boolean,
            viewMode: DashboardViewMode,
            trainingShown: Boolean,
        ): DailyReportPanelGates = DailyReportPanelGates(canReportShopWide, viewMode != DashboardViewMode.TABLES, trainingShown)
    }
}
