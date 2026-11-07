/**
 * Service interface for statistics business logic operations.
 *
 * Defines contracts for business intelligence and analytics operations
 * including data processing, caching, validation, and transformation
 * of statistics data for API consumption.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.statistics.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.auth.Capability

/**
 * Statistics service interface for business intelligence operations.
 *
 * Provides high-level business logic for processing statistics data,
 * including validation, caching, data transformation, and business
 * rule application for POS system analytics.
 */
interface StatisticsService {
    // `callerCapabilities` (operator rollups): the capabilities the caller holds. An operator is
    // named by the HR first + last name only for a caller holding `EMPLOYEE_READ`, otherwise by the
    // username. Defaults to none, so a call site that forgets it shows usernames (fail closed).

    // =========================== SALES STATISTICS ===========================

    suspend fun getSalesOverviewSummary(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
        /**
         * Granularity to compare against — DAY|WEEK|MONTH|YEAR. When non-null and a baseline exists,
         * the response carries a populated `comparison`; the service derives whole-vs-same-elapsed
         * from whether the window is the current period.
         */
        compareGranularity: String? = null,
    ): StatisticsDto.SalesOverviewResponseDto

    /**
     * Revenue by day, by hour of day and by weekday for the selected window.
     *
     * Shares [getSalesOverviewSummary]'s window, employee scope and training flag so the two
     * responses cannot disagree about what counts as revenue.
     */
    suspend fun getSalesTrends(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
    ): StatisticsDto.SalesTrendsResponseDto

    suspend fun getPaginatedBills(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedBillsResponseDto

    suspend fun getPaginatedTableCards(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        page: Int = 0,
        pageSize: Int = 20,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedTableCardsResponseDto

    suspend fun getTableSessions(
        tableId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.TableSessionsResponseDto

    suspend fun getPaginatedEmployeeGroups(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
        callerCapabilities: Set<Capability> = emptySet(),
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto

    suspend fun getPaymentRecords(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
    ): StatisticsDto.PaginatedPaymentsResponseDto

    suspend fun getPaginatedSessions(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        page: Int = 0,
        pageSize: Int = 20,
        trainingOnly: Boolean = false,
        completed: Boolean? = null,
        callerCapabilities: Set<Capability> = emptySet(),
    ): StatisticsDto.PaginatedSessionsResponseDto

    suspend fun getCanceledItems(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
        callerCapabilities: Set<Capability> = emptySet(),
    ): StatisticsDto.CanceledItemsResponseDto

    suspend fun getFinancialOverview(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
    ): StatisticsDto.FinancialOverviewResponseDto

    suspend fun getCatalogBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
        /**
         * Pass a granularity (DAY|WEEK|MONTH|YEAR) to also query the previous like-for-like window
         * and attach per-row growth deltas; null skips the comparison entirely.
         */
        compareGranularity: String? = null,
    ): StatisticsDto.CatalogBreakdownResponseDto

    suspend fun getPromotionBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
        trainingOnly: Boolean = false,
        /**
         * Pass a granularity (DAY|WEEK|MONTH|YEAR) to also query the previous like-for-like window
         * and attach per-row delta percents; null skips the comparison entirely.
         */
        compareGranularity: String? = null,
    ): StatisticsDto.PromotionBreakdownResponseDto
}
