package org.example.project.admin.features.contracts.presentation

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.contract.ContractDto

/** One rendered group: the bucket and the contracts in it, most urgent first. */
data class ContractDueSection(
    val group: ContractDueGroup,
    val contracts: List<ContractDto.ResponseDto>,
)

/** Inside this many days of today counts as "this week". */
private const val THIS_WEEK_DAYS = 7

/** Beyond this many days, a contract is simply "later" — a date that far out is not a to-do. */
private const val THIS_MONTH_DAYS = 30

/**
 * Groups contracts by when they next fall due, urgency first, dropping empty groups.
 *
 * The server returns whatever order it returns, and the one question this screen exists to answer is
 * *when does this fall due*. Grouping is done here rather than server-side because it is a reading of
 * the rows already fetched, not a narrowing of them: no contract is hidden, so no page boundary can
 * lie about what is in the list.
 *
 * A contract with **no** next due date lands in [ContractDueGroup.LATER], never OVERDUE: nothing is
 * outstanding on it, and an unknown date is not a late one.
 *
 * Pure — no clock read inside, so [today] is passed in and the result is testable.
 */
fun groupByDueDate(
    contracts: List<ContractDto.ResponseDto>,
    today: LocalDate,
): List<ContractDueSection> {
    val thisWeekEnd = today.plus(DatePeriod(days = THIS_WEEK_DAYS))
    val thisMonthEnd = today.plus(DatePeriod(days = THIS_MONTH_DAYS))

    return contracts
        .groupBy { it.dueGroup(today, thisWeekEnd, thisMonthEnd) }
        .toList()
        .sortedBy { (group, _) -> group.ordinal }
        .map { (group, rows) -> ContractDueSection(group, rows.sortedBy { it.dueSortKey() }) }
}

private fun ContractDto.ResponseDto.dueGroup(
    today: LocalDate,
    thisWeekEnd: LocalDate,
    thisMonthEnd: LocalDate,
): ContractDueGroup {
    if (!isActive) return ContractDueGroup.INACTIVE
    val due = nextDueDate.parseDay() ?: return ContractDueGroup.LATER
    return when {
        due < today -> ContractDueGroup.OVERDUE
        due <= thisWeekEnd -> ContractDueGroup.THIS_WEEK
        due <= thisMonthEnd -> ContractDueGroup.THIS_MONTH
        else -> ContractDueGroup.LATER
    }
}

/**
 * Orders a group's rows by due date, with the dateless ones last.
 *
 * A plain string sort works because the wire carries ISO dates, which sort chronologically as text —
 * and the sentinel is a date no contract will carry rather than a null, so the comparator stays
 * total.
 */
private fun ContractDto.ResponseDto.dueSortKey(): String = nextDueDate?.substringBefore('T') ?: "9999-12-31"

/** `2027-03-01T00:00` &rarr; a date. Null for a null, blank or unparseable value. */
private fun String?.parseDay(): LocalDate? {
    val day = this?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { LocalDate.parse(day) }.getOrNull()
}
