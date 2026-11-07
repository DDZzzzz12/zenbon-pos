package org.example.project.features.reports.presentation.components.bottomsheet

import org.example.project.core.domain.dto.statistics.StatisticsDto
import kotlin.test.Test
import kotlin.test.assertEquals

class TipEarnersTest {
    private fun group(
        number: String,
        tips: String,
    ) = StatisticsDto.EmployeeGroupDto(
        employeeNumber = number,
        employeeName = "Emp $number",
        totalRevenue = "0.00",
        totalTips = tips,
        billCount = 1,
        bills = emptyList(),
    )

    @Test
    fun ordersByAmountNotAlphabetically() {
        // As strings "9.00" > "10.00"; as money 10.00 is the larger tip.
        val ranked = listOf(group("a", "9.00"), group("b", "10.00")).rankedByTips()

        assertEquals(listOf("b", "a"), ranked.map { it.employeeNumber })
    }

    @Test
    fun anUnparseableAmountRanksLastInsteadOfThrowing() {
        val ranked = listOf(group("a", "n/a"), group("b", "0.50"), group("c", "12.00")).rankedByTips()

        assertEquals(listOf("c", "b", "a"), ranked.map { it.employeeNumber })
    }

    @Test
    fun equalTipsKeepTheirOriginalOrder() {
        val ranked = listOf(group("a", "5.00"), group("b", "5.0"), group("c", "7")).rankedByTips()

        assertEquals(listOf("c", "a", "b"), ranked.map { it.employeeNumber })
    }
}
