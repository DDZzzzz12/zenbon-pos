/**
 * API client interface for Statistics operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.statistics.StatisticsDto

interface StatisticsApiClient {
    suspend fun getSalesOverview(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
        /** Pass "previous" with [granularity] to receive a like-for-like baseline in `comparison`. */
        compare: String? = null,
        /** DAY|WEEK|MONTH|YEAR — the reporting granularity; required for [compare] to take effect. */
        granularity: String? = null,
    ): StatisticsDto.SalesOverviewResponseDto

    /** Time-series trends for the window: daily/hourly revenue and the weekday pattern. */
    suspend fun getSalesTrends(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.SalesTrendsResponseDto

    /** VAT-by-rate and payment-method breakdown for the window (Financial section). */
    suspend fun getFinancialOverview(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.FinancialOverviewResponseDto

    /** Products, categories, and extras breakdown for the window (Katalog section). */
    suspend fun getCatalog(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
        /** Pass "previous" with [granularity] to receive per-row growth rates. */
        compare: String? = null,
        /** DAY|WEEK|MONTH|YEAR — the reporting granularity; required for [compare] to take effect. */
        granularity: String? = null,
    ): StatisticsDto.CatalogBreakdownResponseDto

    /** Per-promotion uptake and deducted amounts for the window (Aktionen section). */
    suspend fun getPromotions(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
        /** Pass "previous" with [granularity] to receive per-promotion delta percents. */
        compare: String? = null,
        /** DAY|WEEK|MONTH|YEAR — the reporting granularity; required for [compare] to take effect. */
        granularity: String? = null,
    ): StatisticsDto.PromotionBreakdownResponseDto

    suspend fun getPaginatedBills(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        page: Int? = null,
        size: Int? = null,
        sort: StatisticsDto.StatisticsSortOption? = null,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedBillsResponseDto

    suspend fun getPaymentRecords(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        page: Int? = null,
        size: Int? = null,
        sort: StatisticsDto.StatisticsSortOption? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.PaginatedPaymentsResponseDto

    suspend fun getPaginatedTableCards(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        page: Int? = null,
        size: Int? = null,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedTableCardsResponseDto

    suspend fun getTableSessions(
        tableId: Long,
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.TableSessionsResponseDto

    suspend fun getPaginatedEmployeeGroups(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        page: Int? = null,
        size: Int? = null,
        sort: StatisticsDto.StatisticsSortOption? = null,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto

    suspend fun getPaginatedSessions(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        page: Int? = null,
        size: Int? = null,
        trainingOnly: Boolean = false,
        completed: Boolean? = null,
    ): StatisticsDto.PaginatedSessionsResponseDto

    suspend fun getCanceledItems(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.CanceledItemsResponseDto
}
