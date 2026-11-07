package org.example.project.features.reports.presentation.components.bottomsheet

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.money.Decimal

/**
 * The payments sheet's order for [option]. Time sorts compare the ISO `paymentTime`; amount sorts
 * compare [StatisticsDto.PaymentRecordDto.amount] as money through the `:platform-money` [Decimal] kernel.
 *
 * The amount sorts used `amount.toLongOrNull()`, which is null for every decimal amount ("16.90"),
 * so Highest and Lowest left the list in its incoming order (UI review follow-up 7). An amount that
 * does not parse counts as zero; equal amounts keep their incoming order (the sort is stable).
 */
fun List<StatisticsDto.PaymentRecordDto>.sortedFor(option: StatisticsDto.StatisticsSortOption): List<StatisticsDto.PaymentRecordDto> =
    when (option) {
        StatisticsDto.StatisticsSortOption.RECENT -> sortedByDescending { it.paymentTime }
        StatisticsDto.StatisticsSortOption.OLDEST -> sortedBy { it.paymentTime }
        StatisticsDto.StatisticsSortOption.HIGHEST -> sortedByDescending { it.amountAsMoney() }
        StatisticsDto.StatisticsSortOption.LOWEST -> sortedBy { it.amountAsMoney() }
    }

private fun StatisticsDto.PaymentRecordDto.amountAsMoney(): Decimal = Decimal.parseOrNull(amount) ?: Decimal.ZERO
