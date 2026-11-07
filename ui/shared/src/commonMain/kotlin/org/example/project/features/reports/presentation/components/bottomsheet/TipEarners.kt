package org.example.project.features.reports.presentation.components.bottomsheet

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.money.Decimal

/**
 * The employees ordered by tips, largest first. Compared as money: [StatisticsDto.EmployeeGroupDto.totalTips]
 * is a decimal STRING, and sorting the strings put "9.00" above "10.00". An amount that does not parse
 * counts as nothing; equal tips keep their incoming order (the sort is stable).
 */
fun List<StatisticsDto.EmployeeGroupDto>.rankedByTips(): List<StatisticsDto.EmployeeGroupDto> =
    sortedByDescending { group -> Decimal.parseOrNull(group.totalTips) ?: Decimal.ZERO }
