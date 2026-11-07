package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto.StatisticsSortOption
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReportFilterSummaryTest {
    private fun count(
        employeeId: String? = null,
        trainingMode: Boolean = false,
        sort: StatisticsSortOption = StatisticsSortOption.RECENT,
        paymentTypes: Set<String> = emptySet(),
    ): Int =
        dailyReportActiveFilterCount(
            employeeId = employeeId,
            trainingMode = trainingMode,
            sort = sort,
            defaultSort = StatisticsSortOption.RECENT,
            paymentTypes = paymentTypes,
        )

    @Test
    fun allDefaultsCountNothing() {
        assertEquals(0, count())
    }

    @Test
    fun aSelectedEmployeeCountsOne() {
        assertEquals(1, count(employeeId = "emp-1"))
    }

    @Test
    fun trainingModeCountsOne() {
        assertEquals(1, count(trainingMode = true))
    }

    @Test
    fun aNonDefaultSortCountsOne() {
        assertEquals(1, count(sort = StatisticsSortOption.HIGHEST))
    }

    @Test
    fun theDefaultSortCountsNothingEvenWhenTheDefaultIsNotRecent() {
        val n =
            dailyReportActiveFilterCount(
                employeeId = null,
                trainingMode = false,
                sort = StatisticsSortOption.OLDEST,
                defaultSort = StatisticsSortOption.OLDEST,
                paymentTypes = emptySet(),
            )
        assertEquals(0, n)
    }

    @Test
    fun thePaymentTypeMultiSelectIsOneFilterHoweverManyTypesAreSelected() {
        assertEquals(0, count(paymentTypes = emptySet()))
        assertEquals(1, count(paymentTypes = setOf("CASH")))
        assertEquals(1, count(paymentTypes = setOf("CASH", "CARD", "VOUCHER")))
    }

    @Test
    fun allFourSetCountFour() {
        assertEquals(
            4,
            count(
                employeeId = "emp-1",
                trainingMode = true,
                sort = StatisticsSortOption.LOWEST,
                paymentTypes = setOf("CASH", "CARD"),
            ),
        )
    }
}
