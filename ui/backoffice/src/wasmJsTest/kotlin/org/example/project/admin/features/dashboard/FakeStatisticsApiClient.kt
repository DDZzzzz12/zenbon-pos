// ui/backoffice/src/wasmJsTest/kotlin/org/example/project/admin/features/dashboard/FakeStatisticsApiClient.kt
package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto

class FakeStatisticsApiClient(
    private val overview: () -> StatisticsDto.SalesOverviewResponseDto,
    private val trends: () -> StatisticsDto.SalesTrendsResponseDto = { throw NotImplementedError() },
    private val financial: () -> StatisticsDto.FinancialOverviewResponseDto = { defaultFinancial() },
    private val catalog: () -> StatisticsDto.CatalogBreakdownResponseDto = { defaultCatalog() },
    private val promotions: () -> StatisticsDto.PromotionBreakdownResponseDto = { defaultPromotions() },
    private val sessions: () -> StatisticsDto.PaginatedSessionsResponseDto = { defaultSessions() },
) : StatisticsApiClient {
    /** Last `compare`/`granularity` observed on [getSalesOverview] (null when never called). */
    var lastOverviewCompare: String? = null
    var lastOverviewGranularity: String? = null

    /** Last `compare`/`granularity` observed on [getCatalog] (null when never called). */
    var lastCatalogCompare: String? = null
    var lastCatalogGranularity: String? = null

    /** Last `compare`/`granularity` observed on [getPromotions] (null when never called). */
    var lastPromotionsCompare: String? = null
    var lastPromotionsGranularity: String? = null

    /** Last `page` observed on [getPaginatedEmployeeGroups] (null when never called). */
    var lastEmployeePage: Int? = null

    /** Last `employeeId` observed across the calls [org.example.project.admin.features.reports.presentation.AnalyticsViewModel.load] makes. */
    var lastEmployeeId: String? = null

    /** Number of times [getSalesOverview] was invoked — used to prove a redundant reload was skipped. */
    var overviewCallCount: Int = 0

    override suspend fun getSalesOverview(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.SalesOverviewResponseDto {
        lastOverviewCompare = compare
        lastOverviewGranularity = granularity
        lastEmployeeId = employeeId
        overviewCallCount++
        return overview()
    }

    override suspend fun getSalesTrends(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ): StatisticsDto.SalesTrendsResponseDto {
        lastEmployeeId = employeeId
        return trends()
    }

    override suspend fun getFinancialOverview(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ): StatisticsDto.FinancialOverviewResponseDto {
        lastEmployeeId = employeeId
        return financial()
    }

    override suspend fun getCatalog(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.CatalogBreakdownResponseDto {
        lastCatalogCompare = compare
        lastCatalogGranularity = granularity
        lastEmployeeId = employeeId
        return catalog()
    }

    override suspend fun getPromotions(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        compare: String?,
        granularity: String?,
    ): StatisticsDto.PromotionBreakdownResponseDto {
        lastPromotionsCompare = compare
        lastPromotionsGranularity = granularity
        lastEmployeeId = employeeId
        return promotions()
    }

    override suspend fun getPaginatedBills(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ) = StatisticsDto.PaginatedBillsResponseDto(
        bills = emptyList(),
        totalAmount = "0.00",
        totalTips = "0.00",
        pagination =
            ApiResponseDto.PaginationInfo(
                currentPage = page ?: 0,
                pageSize = size ?: 20,
                totalItems = 0,
                totalPages = 0,
                hasNext = false,
                hasPrevious = false,
            ),
    )

    override suspend fun getPaymentRecords(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
    ) = throw NotImplementedError()

    override suspend fun getPaginatedTableCards(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ) = throw NotImplementedError()

    override suspend fun getTableSessions(
        tableId: Long,
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ) = throw NotImplementedError()

    var paginatedEmployeeGroups: () -> StatisticsDto.PaginatedEmployeeGroupsResponseDto = {
        StatisticsDto.PaginatedEmployeeGroupsResponseDto(
            employeeGroups = emptyList(),
            pagination =
                ApiResponseDto.PaginationInfo(
                    currentPage = 1,
                    pageSize = 20,
                    totalItems = 0,
                    totalPages = 0,
                    hasNext = false,
                    hasPrevious = false,
                ),
        )
    }

    override suspend fun getPaginatedEmployeeGroups(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        sort: StatisticsDto.StatisticsSortOption?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto {
        lastEmployeePage = page
        return paginatedEmployeeGroups()
    }

    override suspend fun getPaginatedSessions(
        startDate: String,
        endDate: String,
        employeeId: String?,
        page: Int?,
        size: Int?,
        trainingOnly: Boolean,
        completed: Boolean?,
    ) = sessions()

    override suspend fun getCanceledItems(
        startDate: String,
        endDate: String,
        employeeId: String?,
        trainingOnly: Boolean,
    ) = throw NotImplementedError()
}

private fun defaultFinancial() =
    StatisticsDto.FinancialOverviewResponseDto(
        period = "2026-07",
        currency = "EUR",
        grossRevenue = "1000.00",
        netRevenue = "1000.00",
        totalRefunds = "0.00",
        totalTips = "0.00",
        vatBreakdown =
            mapOf(
                "19.00" to
                    VatSummaryDto(
                        vatRate = "19.00",
                        netAmount = "840.34",
                        vatAmount = "159.66",
                        totalAmount = "1000.00",
                        transactionCount = 10,
                    ),
            ),
        paymentMethodBreakdown =
            mapOf(
                "Cash" to
                    PaymentMethodSummaryDto(
                        paymentMethod = "Cash",
                        totalAmount = "1000.00",
                        transactionCount = 10,
                        revenuePercentage = "100.00",
                        averageTransactionValue = "100.00",
                    ),
            ),
        cashFlow =
            StatisticsDto.CashFlowDto(
                openingBalance = "0.00",
                cashSales = "1000.00",
                expenses = "0.00",
                cashRefunds = "0.00",
                netCashFlow = "1000.00",
                closingBalance = "1000.00",
            ),
        dailyFinancialSummary = emptyMap(),
    )

private fun defaultCatalog() =
    StatisticsDto.CatalogBreakdownResponseDto(
        period = "2026-07",
        currency = "EUR",
        bestSellers =
            StatisticsDto.BestSellersResponseDto(
                period = "2026-07",
                topProductsByQuantity = emptyList(),
                topProductsByRevenue = emptyList(),
                fastestGrowingProducts = emptyList(),
                uniqueProductsSold = 0,
            ),
        categories =
            StatisticsDto.CategoryPerformanceResponseDto(
                period = "2026-07",
                categoryMetrics = emptyList(),
                mostProfitableCategory = "",
                fastestGrowingCategory = "",
                categoryRevenueDistribution = emptyMap(),
            ),
        extras = emptyList(),
    )

private fun defaultPromotions() =
    StatisticsDto.PromotionBreakdownResponseDto(
        period = "2026-07",
        totalDeducted = "0.00",
        currency = "EUR",
        promotions = emptyList(),
    )

private fun defaultSessions() =
    StatisticsDto.PaginatedSessionsResponseDto(
        sessions = emptyList(),
        pagination =
            ApiResponseDto.PaginationInfo(
                currentPage = 0,
                pageSize = 20,
                totalItems = 0,
                totalPages = 0,
                hasNext = false,
                hasPrevious = false,
            ),
    )
