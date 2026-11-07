/**
 * Implementation of StatisticsService with comprehensive business logic.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 3.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.statistics.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.EmployeeHrNames
import org.example.project.features.statistics.domain.ComparisonWindow
import org.example.project.features.statistics.domain.ComparisonWindowResolver
import org.example.project.features.statistics.domain.deltaPercent
import org.example.project.features.statistics.domain.repository.StatisticsRepository
import org.example.project.features.statistics.domain.service.StatisticsService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.resolveTargetEmployee
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Statistics service implementation with business logic processing.
 *
 * Implements comprehensive business intelligence operations including
 * data validation, processing, caching, and transformation for POS
 * system analytics and reporting.
 */
class StatisticsServiceImpl(
    private val statisticsRepository: StatisticsRepository,
    private val configManager: IShopConfigurationManager,
) : StatisticsService {
    private val logger = LoggerFactory.getLogger(StatisticsServiceImpl::class.java)

    /**
     * Validates that the requesting employee may access training-only data.
     * Must be invoked inside a [dbQuery] block since it performs an Exposed lookup.
     */
    /*
     * `validateTrainingAccess` used to sit here, refusing `trainingOnly` unless the employee's ROLE
     * was manager-or-admin. It is gone: `parseStatisticsParams` refuses the same request on
     * `TRAINING_REPORT_READ`, which is the capability the training report itself is gated by.
     *
     * Two checks for one decision is not defence in depth when they use different mechanisms — it
     * is two answers that can disagree, and the role-based one now contradicts the group model.
     * The route is the server-side guard; the client half is CLAUDE.md rule 8's other level.
     */

    // =========================== SALES STATISTICS ===========================

    override suspend fun getSalesOverviewSummary(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
        compareGranularity: String?,
    ): StatisticsDto.SalesOverviewResponseDto {
        logger.debug(
            "getSalesOverviewSummary: startDate={}, endDate={}, compareGranularity={}",
            startDate,
            endDate,
            compareGranularity,
        )
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            val current =
                statisticsRepository.getSalesOverviewSummary(
                    startDate,
                    endDate,
                    currency,
                    target,
                    trainingOnly,
                )

            val window =
                compareGranularity?.let { gran ->
                    // The window is partial (the current, in-progress period) exactly when its end
                    // reaches into now's period at this granularity; otherwise it is a complete past
                    // period, which compares to the whole previous one rather than a same-elapsed slice.
                    ComparisonWindowResolver.resolve(startDate, endDate, gran, complete = !windowReachesNow(endDate, gran))
                } ?: return@dbQuery current

            // Same currency, employee scope and training flag as the current period. Anything else
            // makes the two windows incomparable, which is worse than no comparison at all.
            val previous =
                statisticsRepository.getSalesOverviewSummary(
                    window.start,
                    window.end,
                    currency,
                    target,
                    trainingOnly,
                )
            current.copy(comparison = buildComparison(window, current, previous))
        }
    }

    override suspend fun getSalesTrends(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
    ): StatisticsDto.SalesTrendsResponseDto {
        logger.debug(
            "getSalesTrends: startDate={}, endDate={}, employeeId={}, trainingOnly={}",
            startDate,
            endDate,
            employeeId,
            trainingOnly,
        )
        // Same currency the overview resolves. The DTO carries it so the client formats from the
        // response rather than a client-side constant (platform/money/README.md).
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getSalesTrends(startDate, endDate, currency, target, trainingOnly)
        }
    }

    override suspend fun getPaginatedBills(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedBillsResponseDto {
        logger.debug("getPaginatedBills: page={}, pageSize={}, sort={}", page, pageSize, sort)
        return dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getPaginatedBills(
                startDate,
                endDate,
                target,
                page,
                pageSize,
                sort,
                trainingOnly,
                paymentTypes,
            )
        }
    }

    override suspend fun getPaginatedTableCards(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        page: Int,
        pageSize: Int,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedTableCardsResponseDto =
        dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getPaginatedTableCards(
                startDate,
                endDate,
                target,
                page,
                pageSize,
                trainingOnly,
                paymentTypes,
            )
        }

    override suspend fun getTableSessions(
        tableId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.TableSessionsResponseDto =
        dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            StatisticsDto.TableSessionsResponseDto(
                sessions =
                    statisticsRepository.getSessionSummariesForTable(
                        tableId,
                        startDate,
                        endDate,
                        target,
                        trainingOnly,
                        paymentTypes,
                    ),
            )
        }

    override suspend fun getPaginatedEmployeeGroups(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
        callerCapabilities: Set<Capability>,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto =
        dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getPaginatedEmployeeGroups(
                startDate,
                endDate,
                target,
                page,
                pageSize,
                sort,
                trainingOnly,
                paymentTypes,
                hrNames = EmployeeHrNames.visibleTo(callerCapabilities),
            )
        }

    override suspend fun getPaymentRecords(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
    ): StatisticsDto.PaginatedPaymentsResponseDto =
        dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getPaymentRecords(
                startDate,
                endDate,
                target,
                page,
                pageSize,
                sort,
                trainingOnly,
            )
        }

    override suspend fun getPaginatedSessions(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        page: Int,
        pageSize: Int,
        trainingOnly: Boolean,
        completed: Boolean?,
        callerCapabilities: Set<Capability>,
    ): StatisticsDto.PaginatedSessionsResponseDto =
        dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getPaginatedSessions(
                startDate,
                endDate,
                target,
                page,
                pageSize,
                trainingOnly,
                completed,
                hrNames = EmployeeHrNames.visibleTo(callerCapabilities),
            )
        }

    override suspend fun getCanceledItems(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
        callerCapabilities: Set<Capability>,
    ): StatisticsDto.CanceledItemsResponseDto {
        logger.debug("getCanceledItems: startDate={}, endDate={}", startDate, endDate)
        return dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getCanceledItems(
                startDate,
                endDate,
                target,
                trainingOnly,
                hrNames = EmployeeHrNames.visibleTo(callerCapabilities),
            )
        }
    }

    override suspend fun getFinancialOverview(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
    ): StatisticsDto.FinancialOverviewResponseDto {
        logger.debug("getFinancialOverview: startDate={}, endDate={}", startDate, endDate)
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val target = resolveTargetEmployee(employeeId, filterEmployeeId, seesEveryone)
            statisticsRepository.getFinancialOverview(startDate, endDate, currency, target, trainingOnly)
        }
    }

    override suspend fun getCatalogBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
        compareGranularity: String?,
    ): StatisticsDto.CatalogBreakdownResponseDto {
        logger.debug(
            "getCatalogBreakdown: startDate={}, endDate={}, compareGranularity={}",
            startDate,
            endDate,
            compareGranularity,
        )
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val current =
                StatisticsDto.CatalogBreakdownResponseDto(
                    period = "$startDate to $endDate",
                    currency = currency,
                    bestSellers =
                        statisticsRepository
                            .getBestSellers(startDate, endDate, currency, trainingOnly = trainingOnly)
                            .withNullGrowthRates(),
                    categories =
                        statisticsRepository
                            .getCategoryPerformance(startDate, endDate, currency, trainingOnly)
                            .withNullGrowthRates(),
                    extras = statisticsRepository.getExtrasBreakdown(startDate, endDate, currency, trainingOnly),
                )

            val window =
                compareGranularity?.let { gran ->
                    ComparisonWindowResolver.resolve(startDate, endDate, gran, complete = !windowReachesNow(endDate, gran))
                } ?: return@dbQuery current

            val previous =
                StatisticsDto.CatalogBreakdownResponseDto(
                    period = "$startDate to $endDate",
                    currency = currency,
                    bestSellers = statisticsRepository.getBestSellers(window.start, window.end, currency, trainingOnly = trainingOnly),
                    categories = statisticsRepository.getCategoryPerformance(window.start, window.end, currency, trainingOnly),
                    extras = emptyList(),
                )
            current.copy(
                bestSellers =
                    current.bestSellers.copy(
                        topProductsByRevenue =
                            mergeProductGrowth(
                                current.bestSellers.topProductsByRevenue,
                                previous.bestSellers.topProductsByRevenue,
                            ),
                    ),
                categories =
                    current.categories.copy(
                        categoryMetrics =
                            mergeCategoryGrowth(
                                current.categories.categoryMetrics,
                                previous.categories.categoryMetrics,
                            ),
                    ),
            )
        }
    }

    override suspend fun getPromotionBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employeeId: String,
        filterEmployeeId: String?,
        seesEveryone: Boolean,
        trainingOnly: Boolean,
        compareGranularity: String?,
    ): StatisticsDto.PromotionBreakdownResponseDto {
        logger.debug(
            "getPromotionBreakdown: startDate={}, endDate={}, compareGranularity={}",
            startDate,
            endDate,
            compareGranularity,
        )
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val current = statisticsRepository.getPromotionBreakdown(startDate, endDate, currency, trainingOnly)

            val window =
                compareGranularity?.let { gran ->
                    ComparisonWindowResolver.resolve(startDate, endDate, gran, complete = !windowReachesNow(endDate, gran))
                }
            val promotions =
                if (window == null) {
                    current
                } else {
                    val previous = statisticsRepository.getPromotionBreakdown(window.start, window.end, currency, trainingOnly)
                    mergePromotionDeltas(current, previous)
                }
            StatisticsDto.PromotionBreakdownResponseDto(
                period = "$startDate to $endDate",
                totalDeducted =
                    promotions
                        .fold(BigDecimal.ZERO) { acc, p ->
                            acc.add(BigDecimal(p.totalDeducted))
                        }.toPlainString(),
                currency = currency,
                promotions = promotions,
            )
        }
    }

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun buildComparison(
        window: ComparisonWindow,
        current: StatisticsDto.SalesOverviewResponseDto,
        previous: StatisticsDto.SalesOverviewResponseDto,
    ): StatisticsDto.PeriodComparisonDto {
        val curRevenue = BigDecimal(current.totalRevenue)
        val prevRevenue = BigDecimal(previous.totalRevenue)
        val curAverage = averageBill(curRevenue, current.totalBillCount)
        val prevAverage = averageBill(prevRevenue, previous.totalBillCount)
        return StatisticsDto.PeriodComparisonDto(
            previousStart = window.start.toString(),
            previousEnd = window.end.toString(),
            previousTotalRevenue = previous.totalRevenue,
            previousBilledRevenue = previous.billedRevenue,
            previousTotalTips = previous.totalTips,
            previousTotalBillCount = previous.totalBillCount,
            previousTotalSessionCount = previous.totalSessionCount,
            previousAverageBill = prevAverage.toPlainString(),
            revenueDeltaPercent = deltaPercent(curRevenue, prevRevenue),
            billCountDeltaPercent =
                deltaPercent(
                    BigDecimal(current.totalBillCount),
                    BigDecimal(previous.totalBillCount),
                ),
            averageBillDeltaPercent = deltaPercent(curAverage, prevAverage),
            tipsDeltaPercent =
                deltaPercent(
                    BigDecimal(current.totalTips),
                    BigDecimal(previous.totalTips),
                ),
        )
    }

    /** Revenue per bill; zero when there were no bills, so deltaPercent returns null rather than dividing by zero. */
    private fun averageBill(
        revenue: BigDecimal,
        billCount: Int,
    ): BigDecimal =
        if (billCount == 0) {
            BigDecimal.ZERO
        } else {
            revenue.divide(BigDecimal(billCount), 2, RoundingMode.HALF_UP)
        }

    /**
     * Attaches each current product's growth rate against its previous-window revenue.
     *
     * Previous rows are matched by [StatisticsDto.ProductPerformanceDto.productId]; a product with
     * no previous row — or a previous row with zero revenue — gets a null growth rate via
     * [deltaPercent]'s zero guard, which the UI renders as a blank cell.
     */
    private fun mergeProductGrowth(
        current: List<StatisticsDto.ProductPerformanceDto>,
        previous: List<StatisticsDto.ProductPerformanceDto>,
    ): List<StatisticsDto.ProductPerformanceDto> {
        val previousByRevenue = previous.associateBy { it.productId }
        return current.map { product ->
            product.copy(
                growthRate =
                    previousByRevenue[product.productId]?.let { prev ->
                        deltaPercent(BigDecimal(product.revenue), BigDecimal(prev.revenue))
                    },
            )
        }
    }

    /**
     * Same growth merge as [mergeProductGrowth], keyed by
     * [StatisticsDto.CategoryMetricsDto.categoryId].
     */
    private fun mergeCategoryGrowth(
        current: List<StatisticsDto.CategoryMetricsDto>,
        previous: List<StatisticsDto.CategoryMetricsDto>,
    ): List<StatisticsDto.CategoryMetricsDto> {
        val previousByRevenue = previous.associateBy { it.categoryId }
        return current.map { category ->
            category.copy(
                growthRate =
                    previousByRevenue[category.categoryId]?.let { prev ->
                        deltaPercent(BigDecimal(category.totalRevenue), BigDecimal(prev.totalRevenue))
                    },
            )
        }
    }

    /**
     * Attaches prior-period delta percents to each current promotion.
     *
     * Matched by [StatisticsDto.PromotionStatDto.promotionId]; a promotion absent from the previous
     * window gets null deltas (its own previous values are zero) via [deltaPercent]'s zero guard.
     */
    private fun mergePromotionDeltas(
        current: List<StatisticsDto.PromotionStatDto>,
        previous: List<StatisticsDto.PromotionStatDto>,
    ): List<StatisticsDto.PromotionStatDto> {
        val previousByPromotion = previous.associateBy { it.promotionId }
        return current.map { promotion ->
            val prev = previousByPromotion[promotion.promotionId]
            promotion.copy(
                timesAppliedDeltaPercent =
                    prev?.let {
                        deltaPercent(BigDecimal(promotion.timesApplied), BigDecimal(it.timesApplied))
                    },
                totalDeductedDeltaPercent =
                    prev?.let {
                        deltaPercent(BigDecimal(promotion.totalDeducted), BigDecimal(it.totalDeducted))
                    },
            )
        }
    }

    /**
     * True when [end] is at or after the start of *now's* period at [granularity] — i.e. the window
     * is the current, in-progress period. A complete past period is `false` and compares to the whole
     * previous period; a partial current one is `true` and compares to the same elapsed span.
     */
    private fun windowReachesNow(
        end: LocalDateTime,
        granularity: String,
    ): Boolean {
        val now = currentTimeStamp()
        val periodStart =
            when (granularity) {
                "DAY" -> LocalDateTime(now.date, LocalTime(0, 0))
                "WEEK" -> {
                    val monday = now.date.minus(DatePeriod(days = now.date.dayOfWeek.ordinal))
                    LocalDateTime(monday, LocalTime(0, 0))
                }
                "MONTH" -> LocalDateTime(LocalDate(now.year, now.month, 1), LocalTime(0, 0))
                "YEAR" -> LocalDateTime(LocalDate(now.year, Month.JANUARY, 1), LocalTime(0, 0))
                else -> return false
            }
        return end >= periodStart
    }

    private fun StatisticsDto.BestSellersResponseDto.withNullGrowthRates() =
        copy(
            topProductsByQuantity = topProductsByQuantity.map { it.copy(growthRate = null) },
            topProductsByRevenue = topProductsByRevenue.map { it.copy(growthRate = null) },
        )

    private fun StatisticsDto.CategoryPerformanceResponseDto.withNullGrowthRates() =
        copy(
            categoryMetrics = categoryMetrics.map { it.copy(growthRate = null) },
        )
}
