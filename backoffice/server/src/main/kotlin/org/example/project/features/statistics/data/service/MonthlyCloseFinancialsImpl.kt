package org.example.project.features.statistics.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseDailyFinancials
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseFinancialOverview
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseFinancials
import org.example.project.features.statistics.domain.service.StatisticsService

/**
 * The backoffice side of the month-close financials port: the statistics financial overview,
 * narrowed to the figures the fiscal month close reads.
 */
class MonthlyCloseFinancialsImpl(
    private val statisticsService: StatisticsService,
) : MonthlyCloseFinancials {
    override suspend fun overview(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        employeeId: String,
    ): MonthlyCloseFinancialOverview =
        // The Monatsabschluss is shop-wide by definition (route capability MONTHLY_CLOSE). Without
        // seesEveryone the overview is narrowed to the requesting employee's own bills — gross 0 and a
        // negative net for an admin who rang up nothing (E2E-2026-09-18 #9).
        statisticsService
            .getFinancialOverview(periodStart, periodEnd, employeeId, seesEveryone = true)
            .toMonthlyCloseOverview()

    private fun StatisticsDto.FinancialOverviewResponseDto.toMonthlyCloseOverview() =
        MonthlyCloseFinancialOverview(
            currency = currency,
            grossRevenue = grossRevenue,
            netRevenue = netRevenue,
            totalRefunds = totalRefunds,
            totalTips = totalTips,
            vatBreakdown = vatBreakdown,
            paymentMethodBreakdown = paymentMethodBreakdown,
            dailySummary =
                dailyFinancialSummary.mapValues { (_, day) ->
                    MonthlyCloseDailyFinancials(
                        grossRevenue = day.grossRevenue,
                        netRevenue = day.netRevenue,
                        vatAmount = day.vatAmount,
                        transactionCount = day.transactionCount,
                    )
                },
        )
}
