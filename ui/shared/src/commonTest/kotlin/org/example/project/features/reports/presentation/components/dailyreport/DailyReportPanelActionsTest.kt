package org.example.project.features.reports.presentation.components.dailyreport

import org.example.project.core.domain.dto.statistics.StatisticsDto.StatisticsSortOption
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReportPanelActionsTest {
    private val calls = mutableListOf<String>()
    private val actions =
        DailyReportPanelActions(
            onEmployeeSelected = { calls += "employee=$it" },
            onToggleTrainingMode = { calls += "training" },
            onSortChanged = { calls += "sort=$it" },
            onPaymentTypesChanged = { calls += "payments=$it" },
        )
    private val applied = DailyReportPanelFilter(null, false, DailyReportPanelFilter.DEFAULT_SORT, emptySet())

    @Test
    fun anUnchangedTargetSendsNothing() {
        actions.apply(applied, applied)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun sortIsSentFirstThenTheReloadingFields() {
        val sort = StatisticsSortOption.entries.first { it != DailyReportPanelFilter.DEFAULT_SORT }
        actions.apply(applied, DailyReportPanelFilter("e1", true, sort, setOf("CASH")))
        assertEquals(listOf("sort=$sort", "employee=e1", "training", "payments=[CASH]"), calls)
    }

    @Test
    fun withoutATrainingToggleTheTrainingChangeIsDropped() {
        val noTraining = actions.copy(onToggleTrainingMode = null)
        noTraining.apply(applied, applied.copy(trainingMode = true))
        assertEquals(emptyList(), calls)
    }
}
