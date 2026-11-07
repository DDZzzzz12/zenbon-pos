package org.example.project.admin.features.contracts

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.contracts.presentation.ContractDueGroup
import org.example.project.admin.features.contracts.presentation.groupByDueDate
import org.example.project.core.domain.dto.contract.ContractDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TODAY = LocalDate(2027, 3, 15)

/**
 * Named `dueContract`, not `contract`: `ContractsViewModelTest` declares an `internal fun contract`
 * in this same package whose second parameter is a non-null `String` name. A `contract(1, "2027-…")`
 * call resolves to **that** overload — `String` is more specific than `String?` — so the date landed
 * in the name and every fixture came back with a null due date, silently.
 */
private fun dueContract(
    id: Long,
    nextDue: String?,
    active: Boolean = true,
    name: String = "Vertrag $id",
) = ContractDto.ResponseDto(
    id = id,
    name = name,
    interval = "MONTHLY",
    dueDay = 1,
    startDate = "2027-01-01T00:00",
    nextDueDate = nextDue,
    isActive = active,
)

/**
 * Verträge answers one question — what falls due, and when — so the list is grouped by that rather
 * than left in whatever order the server returned.
 *
 * Pure and testable on purpose: the grouping decides what an operator sees first, and asserting it
 * through a composition would mean asserting nothing at all, since adminApp has no UI test target.
 */
class ContractDueGroupTest {
    @Test
    fun `a due date in the past is overdue`() {
        val grouped = groupByDueDate(listOf(dueContract(1, "2027-03-01T00:00")), TODAY)

        assertEquals(listOf(ContractDueGroup.OVERDUE), grouped.map { it.group })
        assertEquals(listOf(1L), grouped.single().contracts.map { it.id })
    }

    /** Due *today* is not overdue — the payment has not been missed until the day is out. */
    @Test
    fun `today is due this week, not overdue`() {
        val grouped = groupByDueDate(listOf(dueContract(1, "2027-03-15T00:00")), TODAY)

        assertEquals(listOf(ContractDueGroup.THIS_WEEK), grouped.map { it.group })
    }

    @Test
    fun `the next seven days are this week and the rest of the month is this month`() {
        val contracts =
            listOf(
                dueContract(1, "2027-03-20T00:00"),
                dueContract(2, "2027-03-28T00:00"),
            )

        val grouped = groupByDueDate(contracts, TODAY)

        assertEquals(listOf(ContractDueGroup.THIS_WEEK, ContractDueGroup.THIS_MONTH), grouped.map { it.group })
        assertEquals(listOf(1L), grouped[0].contracts.map { it.id })
        assertEquals(listOf(2L), grouped[1].contracts.map { it.id })
    }

    @Test
    fun `anything beyond thirty days is later`() {
        val grouped = groupByDueDate(listOf(dueContract(1, "2027-09-01T00:00")), TODAY)

        assertEquals(listOf(ContractDueGroup.LATER), grouped.map { it.group })
    }

    /**
     * An inactive contract is not a thing that falls due, so it never joins a date group — putting a
     * cancelled contract under „Überfällig" would invent an obligation that no longer exists.
     */
    @Test
    fun `an inactive contract is grouped as inactive whatever its due date says`() {
        val grouped = groupByDueDate(listOf(dueContract(1, "2027-01-01T00:00", active = false)), TODAY)

        assertEquals(listOf(ContractDueGroup.INACTIVE), grouped.map { it.group })
    }

    /** A contract with no next due date is not overdue — unknown is not late. */
    @Test
    fun `a contract with no due date falls into later rather than overdue`() {
        val grouped = groupByDueDate(listOf(dueContract(1, null)), TODAY)

        assertEquals(listOf(ContractDueGroup.LATER), grouped.map { it.group })
    }

    @Test
    fun `groups come back in urgency order and empty ones are omitted`() {
        val contracts =
            listOf(
                dueContract(1, "2027-09-01T00:00"),
                dueContract(2, "2027-03-01T00:00"),
                dueContract(3, "2027-03-16T00:00"),
            )

        val grouped = groupByDueDate(contracts, TODAY)

        assertEquals(
            listOf(ContractDueGroup.OVERDUE, ContractDueGroup.THIS_WEEK, ContractDueGroup.LATER),
            grouped.map { it.group },
            "no THIS_MONTH group, because nothing falls in it",
        )
    }

    /** Within a group the most urgent comes first, so the top row is always the next thing to pay. */
    @Test
    fun `contracts inside a group are ordered by due date`() {
        val contracts =
            listOf(
                dueContract(1, "2027-03-05T00:00"),
                dueContract(2, "2027-03-01T00:00"),
                dueContract(3, "2027-03-10T00:00"),
            )

        val grouped = groupByDueDate(contracts, TODAY)

        assertEquals(listOf(2L, 1L, 3L), grouped.single().contracts.map { it.id })
    }

    @Test
    fun `an empty list produces no groups at all`() {
        assertTrue(groupByDueDate(emptyList(), TODAY).isEmpty())
    }
}
