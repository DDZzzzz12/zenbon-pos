package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto

/**
 * The badge count of the daily report's Filter button: how many panel filters are applied. The
 * period is not counted (it is always set and always visible in the bar), and the payment-type
 * multi-select is ONE filter however many types it holds.
 *
 * Callers pass a filter the panel does not offer as its default (no employee without the shop-wide
 * capability, no training without the toggle, the default sort on the Tische view), so a value the
 * operator cannot see or change never shows as active.
 */
fun dailyReportActiveFilterCount(
    employeeId: String?,
    trainingMode: Boolean,
    sort: StatisticsDto.StatisticsSortOption,
    defaultSort: StatisticsDto.StatisticsSortOption,
    paymentTypes: Set<String>,
): Int =
    listOf(
        employeeId != null,
        trainingMode,
        sort != defaultSort,
        paymentTypes.isNotEmpty(),
    ).count { it }
