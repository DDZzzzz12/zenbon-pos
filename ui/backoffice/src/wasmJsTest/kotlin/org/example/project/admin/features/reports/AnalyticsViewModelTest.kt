package org.example.project.admin.features.reports

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.dashboard.FakeStatisticsApiClient
import org.example.project.admin.features.reports.presentation.AnalyticsViewModel
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An overview whose comparison carries [delta] as its revenue delta; totalRevenue is always "1000.00". */
private fun overviewWithComparison(delta: String?) =
    StatisticsDto.SalesOverviewResponseDto(
        period = "2026-07",
        totalRevenue = "1000.00",
        billedRevenue = "1000.00",
        canceledRevenue = "0.00",
        openItemAmount = "0.00",
        totalTips = "0.00",
        totalBillCount = 10,
        comparison =
            StatisticsDto.PeriodComparisonDto(
                previousStart = "2026-06-01T00:00:00",
                previousEnd = "2026-06-30T23:59:59",
                previousTotalRevenue = "900.00",
                previousBilledRevenue = "900.00",
                previousTotalTips = "0.00",
                previousTotalBillCount = 9,
                previousTotalSessionCount = 9,
                previousAverageBill = "100.00",
                revenueDeltaPercent = delta,
            ),
    )

/** An overview with no baseline at all (server omitted `comparison`). */
private fun overviewWithoutComparison() =
    StatisticsDto.SalesOverviewResponseDto(
        period = "2026-07",
        totalRevenue = "1000.00",
        billedRevenue = "1000.00",
        canceledRevenue = "0.00",
        openItemAmount = "0.00",
        totalTips = "0.00",
        totalBillCount = 10,
        comparison = null,
    )

private fun trends() =
    StatisticsDto.SalesTrendsResponseDto(
        startDate = "2026-07-01",
        endDate = "2026-07-31",
        dailyRevenue = emptyMap(),
        dailyTransactionCounts = emptyMap(),
        hourlyRevenue = emptyMap(),
        growthRate = "0.00",
        bestPerformingDays = emptyList(),
        weeklyPattern = emptyMap(),
    )

private fun financial() =
    StatisticsDto.FinancialOverviewResponseDto(
        period = "2026-07",
        currency = "EUR",
        grossRevenue = "1000.00",
        netRevenue = "1000.00",
        totalRefunds = "0.00",
        totalTips = "0.00",
        vatBreakdown = emptyMap(),
        paymentMethodBreakdown = emptyMap(),
        cashFlow =
            StatisticsDto.CashFlowDto(
                openingBalance = "0.00",
                cashSales = "0.00",
                expenses = "0.00",
                cashRefunds = "0.00",
                netCashFlow = "0.00",
                closingBalance = "0.00",
            ),
        dailyFinancialSummary = emptyMap(),
    )

private fun catalog() =
    StatisticsDto.CatalogBreakdownResponseDto(
        period = "2026-06",
        currency = "EUR",
        bestSellers =
            StatisticsDto.BestSellersResponseDto(
                period = "2026-06",
                topProductsByQuantity = emptyList(),
                topProductsByRevenue =
                    listOf(
                        StatisticsDto.ProductPerformanceDto(
                            productId = 1,
                            productName = "Cola",
                            categoryName = "Drinks",
                            quantitySold = 12,
                            revenue = "24.00",
                            salesPercentage = "0.00",
                            growthRate = "0.00",
                        ),
                    ),
                fastestGrowingProducts = emptyList(),
                uniqueProductsSold = 1,
            ),
        categories =
            StatisticsDto.CategoryPerformanceResponseDto(
                period = "2026-06",
                categoryMetrics = emptyList(),
                mostProfitableCategory = "Drinks",
                fastestGrowingCategory = "",
                categoryRevenueDistribution = mapOf("Drinks" to "24.00"),
            ),
        extras =
            listOf(
                StatisticsDto.ExtraOptionStatDto(optionName = "Extra shot", groupName = "Coffee", timesChosen = 5, addedRevenue = "5.00"),
            ),
    )

private fun promotions() =
    StatisticsDto.PromotionBreakdownResponseDto(
        period = "2026-06",
        totalDeducted = "11.50",
        currency = "EUR",
        promotions =
            listOf(
                StatisticsDto.PromotionStatDto(
                    promotionId = 1,
                    name = "Happy Hour -20%",
                    discountType = "PERCENTAGE",
                    timesApplied = 2,
                    totalDeducted = "8.00",
                ),
                StatisticsDto.PromotionStatDto(
                    promotionId = 2,
                    name = "Free drink",
                    discountType = "FREE_ITEM",
                    timesApplied = 1,
                    totalDeducted = "3.50",
                ),
            ),
    )

/** A catalog whose product row carries [growth] (null = toggle off / no baseline). */
private fun catalogWithGrowth(growth: String?) =
    StatisticsDto.CatalogBreakdownResponseDto(
        period = "2026-06",
        currency = "EUR",
        bestSellers =
            StatisticsDto.BestSellersResponseDto(
                period = "2026-06",
                topProductsByQuantity = emptyList(),
                topProductsByRevenue =
                    listOf(
                        StatisticsDto.ProductPerformanceDto(
                            productId = 1,
                            productName = "Cola",
                            categoryName = "Drinks",
                            quantitySold = 12,
                            revenue = "24.00",
                            salesPercentage = "0.00",
                            growthRate = growth,
                        ),
                    ),
                fastestGrowingProducts = emptyList(),
                uniqueProductsSold = 1,
            ),
        categories =
            StatisticsDto.CategoryPerformanceResponseDto(
                period = "2026-06",
                categoryMetrics = emptyList(),
                mostProfitableCategory = "Drinks",
                fastestGrowingCategory = "",
                categoryRevenueDistribution = mapOf("Drinks" to "24.00"),
            ),
        extras = emptyList(),
    )

/** Promotions whose first row carries the given delta percents (null = toggle off / no baseline). */
private fun promotionsWithDeltas(
    timesDelta: String?,
    deductedDelta: String?,
) = StatisticsDto.PromotionBreakdownResponseDto(
    period = "2026-06",
    totalDeducted = "11.50",
    currency = "EUR",
    promotions =
        listOf(
            StatisticsDto.PromotionStatDto(
                promotionId = 1,
                name = "Happy Hour -20%",
                discountType = "PERCENTAGE",
                timesApplied = 2,
                totalDeducted = "8.00",
                timesAppliedDeltaPercent = timesDelta,
                totalDeductedDeltaPercent = deductedDelta,
            ),
        ),
)

class AnalyticsViewModelTest {
    private val thisMonth = LocalDate(2026, 6, 15)
    private val aPastMonth = LocalDate(2025, 3, 15)

    @Test
    fun comparisonPresent_exposesTheRevenueDelta() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(overview = { overviewWithComparison("12.40") }, trends = ::trends),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertEquals("12.40", vm.state.revenueDeltaPercent)
        }

    @Test
    fun comparisonAbsent_exposesNoDelta() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(overview = ::overviewWithoutComparison, trends = ::trends),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertNull(vm.state.revenueDeltaPercent)
        }

    @Test
    fun comparisonPresentButDeltaNull_stillExposesTheFigure() =
        runTest {
            // A zero baseline yields a null percentage inside a PRESENT comparison. The figure must
            // still be there — only the chip disappears.
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(overview = { overviewWithComparison(null) }, trends = ::trends),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertNull(vm.state.revenueDeltaPercent)
            assertEquals("1000.00", vm.state.overview?.totalRevenue)
        }

    @Test
    fun pastPeriod_stillPopulatesTheOverview() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(overview = { overviewWithComparison("5.00") }, trends = ::trends),
                )
            vm.load(ReportsGranularity.MONTH, aPastMonth)
            assertEquals("1000.00", vm.state.overview?.totalRevenue)
            assertEquals(aPastMonth, vm.state.anchor)
        }

    @Test
    fun load_populatesFinancialState() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(overview = ::overviewWithoutComparison, trends = ::trends, financial = ::financial),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertEquals("EUR", vm.state.financial?.currency)
            assertEquals("1000.00", vm.state.financial?.grossRevenue)
        }

    @Test
    fun load_populatesCatalogState() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(
                        overview = ::overviewWithoutComparison,
                        trends = ::trends,
                        financial = ::financial,
                        catalog = ::catalog,
                    ),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertEquals("EUR", vm.state.catalog?.currency)
            assertEquals(
                "Cola",
                vm.state.catalog
                    ?.bestSellers
                    ?.topProductsByRevenue
                    ?.firstOrNull()
                    ?.productName,
            )
            assertEquals(
                1,
                vm.state.catalog
                    ?.extras
                    ?.size,
            )
        }

    @Test
    fun load_populatesPromotionsState() =
        runTest {
            val vm =
                AnalyticsViewModel(
                    FakeStatisticsApiClient(
                        overview = ::overviewWithoutComparison,
                        trends = ::trends,
                        financial = ::financial,
                        catalog = ::catalog,
                        promotions = ::promotions,
                    ),
                )
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertEquals("EUR", vm.state.promotions?.currency)
            assertEquals(
                "Happy Hour -20%",
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.name,
            )
            assertEquals("11.50", vm.state.promotions?.totalDeducted)
        }

    @Test
    fun toggleOffByDefault_sendsNoCompareParamsAndNoDeltas() =
        runTest {
            val fake =
                FakeStatisticsApiClient(
                    overview = ::overviewWithoutComparison,
                    trends = ::trends,
                    financial = ::financial,
                    catalog = { catalogWithGrowth(null) },
                    promotions = { promotionsWithDeltas(null, null) },
                )
            val vm = AnalyticsViewModel(fake)
            vm.load(ReportsGranularity.MONTH, thisMonth)

            assertNull(fake.lastOverviewCompare)
            assertNull(fake.lastOverviewGranularity)
            assertNull(fake.lastCatalogCompare)
            assertNull(fake.lastCatalogGranularity)
            assertNull(fake.lastPromotionsCompare)
            assertNull(fake.lastPromotionsGranularity)
            assertNull(vm.state.revenueDeltaPercent)
            assertNull(
                vm.state.catalog
                    ?.bestSellers
                    ?.topProductsByRevenue
                    ?.firstOrNull()
                    ?.growthRate,
            )
            assertNull(
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.timesAppliedDeltaPercent,
            )
            assertNull(
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.totalDeductedDeltaPercent,
            )
        }

    @Test
    fun toggleOn_sendsPreviousWithGranularity_andExposesDeltas() =
        runTest {
            val fake =
                FakeStatisticsApiClient(
                    overview = { overviewWithComparison("12.40") },
                    trends = ::trends,
                    financial = ::financial,
                    catalog = { catalogWithGrowth("12.50") },
                    promotions = { promotionsWithDeltas("100.00", "33.33") },
                )
            val vm = AnalyticsViewModel(fake)
            vm.load(ReportsGranularity.MONTH, thisMonth)
            vm.setCompareEnabled(true)

            assertEquals("previous", fake.lastOverviewCompare)
            assertEquals("MONTH", fake.lastOverviewGranularity)
            assertEquals("previous", fake.lastCatalogCompare)
            assertEquals("MONTH", fake.lastCatalogGranularity)
            assertEquals("previous", fake.lastPromotionsCompare)
            assertEquals("MONTH", fake.lastPromotionsGranularity)
            assertEquals("12.40", vm.state.revenueDeltaPercent)
            assertEquals(
                "12.50",
                vm.state.catalog
                    ?.bestSellers
                    ?.topProductsByRevenue
                    ?.firstOrNull()
                    ?.growthRate,
            )
            assertEquals(
                "100.00",
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.timesAppliedDeltaPercent,
            )
            assertEquals(
                "33.33",
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.totalDeductedDeltaPercent,
            )
        }

    @Test
    fun toggleOff_afterOn_restoresOffState() =
        runTest {
            lateinit var fake: FakeStatisticsApiClient
            fake =
                FakeStatisticsApiClient(
                    overview = {
                        if (fake.lastOverviewCompare ==
                            "previous"
                        ) {
                            overviewWithComparison("12.40")
                        } else {
                            overviewWithoutComparison()
                        }
                    },
                    trends = ::trends,
                    financial = ::financial,
                    catalog = { if (fake.lastCatalogCompare == "previous") catalogWithGrowth("12.50") else catalogWithGrowth(null) },
                    promotions = {
                        if (fake.lastPromotionsCompare ==
                            "previous"
                        ) {
                            promotionsWithDeltas("100.00", "33.33")
                        } else {
                            promotionsWithDeltas(null, null)
                        }
                    },
                )
            val vm = AnalyticsViewModel(fake)
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertNull(vm.state.revenueDeltaPercent)

            vm.setCompareEnabled(true)
            assertEquals("previous", fake.lastOverviewCompare)
            assertEquals("MONTH", fake.lastOverviewGranularity)
            assertEquals("12.40", vm.state.revenueDeltaPercent)
            assertEquals(
                "12.50",
                vm.state.catalog
                    ?.bestSellers
                    ?.topProductsByRevenue
                    ?.firstOrNull()
                    ?.growthRate,
            )
            assertEquals(
                "100.00",
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.timesAppliedDeltaPercent,
            )
            assertEquals(
                "33.33",
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.totalDeductedDeltaPercent,
            )

            vm.setCompareEnabled(false)
            assertNull(fake.lastOverviewCompare)
            assertNull(fake.lastOverviewGranularity)
            assertNull(fake.lastCatalogCompare)
            assertNull(fake.lastPromotionsCompare)
            assertNull(vm.state.revenueDeltaPercent)
            assertNull(
                vm.state.catalog
                    ?.bestSellers
                    ?.topProductsByRevenue
                    ?.firstOrNull()
                    ?.growthRate,
            )
            assertNull(
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.timesAppliedDeltaPercent,
            )
            assertNull(
                vm.state.promotions
                    ?.promotions
                    ?.firstOrNull()
                    ?.totalDeductedDeltaPercent,
            )
        }

    @Test
    fun employeeFilter_isSentAndTriggersAReload() =
        runTest {
            val fake =
                FakeStatisticsApiClient(
                    overview = ::overviewWithoutComparison,
                    trends = ::trends,
                    financial = ::financial,
                    catalog = ::catalog,
                    promotions = ::promotions,
                )
            val vm = AnalyticsViewModel(fake)
            vm.load(ReportsGranularity.MONTH, thisMonth)
            assertEquals(1, fake.overviewCallCount)

            vm.selectEmployee("uuid-7")
            assertEquals("uuid-7", vm.state.employeeId)
            assertEquals("uuid-7", fake.lastEmployeeId)
            assertEquals(2, fake.overviewCallCount)
        }

    @Test
    fun employeeFilter_reselectingTheSameEmployee_doesNotRefetch() =
        runTest {
            val fake =
                FakeStatisticsApiClient(
                    overview = ::overviewWithoutComparison,
                    trends = ::trends,
                    financial = ::financial,
                    catalog = ::catalog,
                    promotions = ::promotions,
                )
            val vm = AnalyticsViewModel(fake)
            vm.load(ReportsGranularity.MONTH, thisMonth)
            vm.selectEmployee("uuid-7")
            assertEquals(2, fake.overviewCallCount)

            vm.selectEmployee("uuid-7")
            assertEquals(2, fake.overviewCallCount)
        }
}
