package org.example.project.admin.features.dashboard.presentation.sections

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.money.Decimal

/**
 * [failed] and an empty [overview] are different things and must render differently: a failed load
 * says so, it does not render zeros.
 *
 * [hasRevenue] is decided in `Decimal`, never by parsing the server string into a `Double` — that is
 * the defect the `:platform-money` module exists to remove. An unparseable amount counts as no revenue.
 */
data class DayFiguresState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val overview: StatisticsDto.SalesOverviewResponseDto? = null,
) {
    val hasRevenue: Boolean
        get() = overview?.let { Decimal.parseOrNull(it.totalRevenue)?.isZero == false } ?: false
}
