/**
 * Repository interface for statistics data access.
 *
 * Defines contracts for retrieving business intelligence and analytics data
 * from the POS system database. Provides aggregated data for sales, products,
 * employees, tables, and financial reporting.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.statistics.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.features.employee.domain.models.Employee

/**
 * Statistics repository interface for business intelligence data access.
 *
 * Provides methods for retrieving aggregated statistics across all
 * POS system entities including sales, products, employees, tables, and financial data.
 * All methods run within a transaction context provided by the caller.
 */
interface StatisticsRepository {
    // =========================== SALES STATISTICS ===========================

    /**
     * Retrieves the sales overview for a period: aggregated totals only, no session/bill lists.
     *
     * Serves `GET /statistics/sales/overview`, which the POS reports screen, the admin dashboard and
     * the admin reports screen all read. Bills are attributed by their Z-Bon's close rather than
     * their own timestamp — see [buildBillFilterClause]'s callers for the convention.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param currency ISO 4217 code the response's amounts are denominated in, resolved once per
     *        request by the service from shop config. Required and undefaulted on purpose: the DTO
     *        default and the config fallback are both "EUR", so a forgotten call site would
     *        otherwise look correct.
     * @param employee Employee to filter personal statistics
     * @return Lightweight sales overview data
     */
    fun getSalesOverviewSummary(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.SalesOverviewResponseDto

    /**
     * Retrieves paginated bills for the BILLS view mode.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param page Page number (0-indexed)
     * @param pageSize Number of items per page
     * @param sort Sort option for ordering results
     * @return Paginated bills
     */
    fun getPaginatedBills(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedBillsResponseDto

    /**
     * Retrieves paginated table cards for the TABLES view mode.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param page Page number (0-indexed)
     * @param pageSize Number of cards per page
     * @return Paginated table cards
     */
    fun getPaginatedTableCards(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        page: Int = 0,
        pageSize: Int = 20,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): StatisticsDto.PaginatedTableCardsResponseDto

    /**
     * Retrieves session summaries for a specific table.
     *
     * @param tableId The table ID to get sessions for
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @return List of session summaries with embedded bills
     */
    fun getSessionSummariesForTable(
        tableId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
    ): List<StatisticsDto.TableSessionSummaryDto>

    /**
     * Retrieves paginated employee groups for the EMPLOYEES view mode.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param page Page number (0-indexed)
     * @param pageSize Number of groups per page
     * @param hrNames whether operators are named by their HR first + last name (the caller holds
     *   `EMPLOYEE_READ`); otherwise by the username label. Defaults to the username: fail closed.
     * @return Paginated employee groups
     */
    fun getPaginatedEmployeeGroups(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
        paymentTypes: List<String>? = null,
        hrNames: Boolean = false,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto

    /**
     * Retrieves paginated sessions for the SESSIONS view mode.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param page Page number (0-indexed)
     * @param pageSize Number of sessions per page
     * @param hrNames whether operators are named by their HR first + last name (the caller holds
     *   `EMPLOYEE_READ`); otherwise by the username label. Defaults to the username: fail closed.
     * @return Paginated sessions
     */
    fun getPaginatedSessions(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        page: Int = 0,
        pageSize: Int = 20,
        trainingOnly: Boolean = false,
        completed: Boolean? = null,
        hrNames: Boolean = false,
    ): StatisticsDto.PaginatedSessionsResponseDto

    /**
     * Retrieves paginated payment records for the PAYMENTS view.
     *
     * Queries the payment table joined with bill and payment type tables
     * to return individual payment transactions within the time range.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param page Page number (0-indexed)
     * @param pageSize Number of items per page
     * @param sort Sort option for ordering results
     * @return Paginated payment records
     */
    fun getPaymentRecords(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        page: Int = 0,
        pageSize: Int = 20,
        sort: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
        trainingOnly: Boolean = false,
    ): StatisticsDto.PaginatedPaymentsResponseDto

    /**
     * Retrieves sales trends analysis with time-series data.
     *
     * Answers "when is the shop busy" — revenue per day, per hour of day and per weekday.
     * Takes the same [LocalDateTime] window, employee scope and training flag as
     * [getSalesOverviewSummary], and applies the identical bill filter, so the trend series always
     * sums to that endpoint's headline total. A chart that disagrees with the total above it is
     * worse than either number alone.
     *
     * No `currency` parameter, unlike [getSalesOverviewSummary]: [StatisticsDto.SalesTrendsResponseDto]
     * has no currency field to put it in.
     *
     * @param startDate Start date for trend analysis
     * @param endDate End date for trend analysis
     * @param employee Employee to filter personal statistics
     * @return Sales trends data
     */
    fun getSalesTrends(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.SalesTrendsResponseDto

    /**
     * Retrieves hourly sales distribution for peak hours analysis.
     *
     * @param date Specific date for hourly analysis
     * @return Hourly revenue distribution
     */
    fun getHourlySalesDistribution(date: LocalDate): Map<Int, String>

    // =========================== PRODUCT STATISTICS ===========================

    /**
     * Retrieves best-selling products analysis.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param currency ISO 4217 code accepted for contract symmetry with [getFinancialOverview]; the
     *        service echoes it onto the wrapper DTO. The query math stays [java.math.BigDecimal].
     * @param limit Maximum number of products to return
     * @return Best sellers data
     */
    fun getBestSellers(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        limit: Int = 10,
        trainingOnly: Boolean = false,
    ): StatisticsDto.BestSellersResponseDto

    /**
     * Retrieves product category performance analysis.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param currency ISO 4217 code accepted for contract symmetry with [getFinancialOverview]; the
     *        service echoes it onto the wrapper DTO. The query math stays [java.math.BigDecimal].
     * @return Category performance data
     */
    fun getCategoryPerformance(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean = false,
    ): StatisticsDto.CategoryPerformanceResponseDto

    /**
     * Retrieves extras (item-option) uptake for the window, from the snapshotted item_extras rows.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param currency ISO 4217 code accepted for contract symmetry; the service echoes it onto the
     *        wrapper DTO. The query math stays [java.math.BigDecimal].
     * @return One [StatisticsDto.ExtraOptionStatDto] per option, ordered by added revenue desc
     */
    fun getExtrasBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean = false,
    ): List<StatisticsDto.ExtraOptionStatDto>

    /**
     * Returns per-promotion uptake (count + Σ deducted) for one window.
     *
     * Attributes by the bill's Z-Bon close time via [attributedTimeExpr] semantics, mirroring the
     * catalog queries. Grouped by promotion id/name/discount type, ordered by total deducted desc.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param currency ISO 4217 code accepted for contract symmetry; the service echoes it onto the
     *        wrapper DTO. The query math stays [java.math.BigDecimal].
     * @param trainingOnly When true, only training bills are counted
     * @return One [StatisticsDto.PromotionStatDto] per promotion, ordered by total deducted desc
     */
    fun getPromotionBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean = false,
    ): List<StatisticsDto.PromotionStatDto>

    /**
     * Retrieves individual product performance metrics.
     *
     * @param productId Specific product ID to analyze
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @return Product performance data
     */
    fun getProductPerformance(
        productId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): StatisticsDto.ProductPerformanceDto

    // =========================== FINANCIAL STATISTICS ===========================

    /**
     * Retrieves comprehensive financial overview.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param filters Optional filters to apply to the query
     * @return Financial overview data
     */
    fun getFinancialOverview(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee? = null,
        trainingOnly: Boolean = false,
    ): StatisticsDto.FinancialOverviewResponseDto

    /**
     * Retrieves canceled items for the given date range and optional employee filter.
     *
     * @param startDate Start date for the analysis period
     * @param endDate End date for the analysis period
     * @param employee Employee to filter by (null = all employees)
     * @param hrNames whether operators are named by their HR first + last name (the caller holds
     *   `EMPLOYEE_READ`); otherwise by the username label. Defaults to the username: fail closed.
     * @return Canceled items response with totals
     */
    fun getCanceledItems(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee? = null,
        trainingOnly: Boolean = false,
        hrNames: Boolean = false,
    ): StatisticsDto.CanceledItemsResponseDto

    // =========================== UTILITY METHODS ===========================

    /**
     * Retrieves the date range of available data in the system.
     *
     * @return The earliest and latest data dates
     */
    fun getDataDateRange(): Pair<LocalDate, LocalDate>
}
