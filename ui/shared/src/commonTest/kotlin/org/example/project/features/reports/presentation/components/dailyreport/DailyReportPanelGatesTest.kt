package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto.StatisticsSortOption
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReportPanelGatesTest {
    private val busy =
        DailyReportPanelFilter(
            employeeId = "emp-1",
            trainingMode = true,
            sort = StatisticsSortOption.entries.first { it != DailyReportPanelFilter.DEFAULT_SORT },
            paymentTypes = setOf("CASH"),
        )

    private val everything = DailyReportPanelGates(canReportShopWide = true, sortShown = true, trainingShown = true)
    private val nothing = DailyReportPanelGates(canReportShopWide = false, sortShown = false, trainingShown = false)

    @Test
    fun withEveryGateOpenTheEffectiveFilterIsTheAppliedOne() {
        assertEquals(busy, everything.effective(busy))
    }

    @Test
    fun aValueTheOperatorCannotSeeIsNotEffective() {
        assertEquals(
            DailyReportPanelFilter(null, false, DailyReportPanelFilter.DEFAULT_SORT, setOf("CASH")),
            nothing.effective(busy),
        )
    }

    @Test
    fun clearingResetsEveryOfferedField() {
        assertEquals(
            DailyReportPanelFilter(null, false, DailyReportPanelFilter.DEFAULT_SORT, emptySet()),
            everything.cleared(busy),
        )
    }

    @Test
    fun clearingLeavesFieldsThePanelDoesNotOffer() {
        // Payment types are always offered when present; the gated three stay as applied.
        assertEquals(busy.copy(paymentTypes = emptySet()), nothing.cleared(busy))
    }
}
