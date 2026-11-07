package org.example.project.admin.features.reports.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.reports.presentation.ReportsGranularity

data class ReportsState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val granularity: ReportsGranularity = ReportsGranularity.DAY,
    /** The date identifying which day/week/month/year is shown; the VM seeds it with today. */
    val anchor: LocalDate,
    /** When true the screen compares against the previous like-for-like period (compare=previous). */
    val compareEnabled: Boolean = false,
    /**
     * UUID of the employee the report is filtered to, or null for all employees. Kept here rather than
     * on [org.example.project.features.employee.presentation.EmployeePickerViewModel] — the whole app
     * shares one `ViewModelStoreOwner`, so that VM's roster is shared across screens but each screen's
     * selection must not leak into another (mirrors `VoucherState.employeeId`).
     */
    val employeeId: String? = null,
    val overview: StatisticsDto.SalesOverviewResponseDto? = null,
    val trends: StatisticsDto.SalesTrendsResponseDto? = null,
    val financial: StatisticsDto.FinancialOverviewResponseDto? = null,
    val catalog: StatisticsDto.CatalogBreakdownResponseDto? = null,
    val promotions: StatisticsDto.PromotionBreakdownResponseDto? = null,
) {
    /** Convenience: the revenue delta from the comparison, or null when there is no baseline. */
    val revenueDeltaPercent: String? get() = overview?.comparison?.revenueDeltaPercent
}
