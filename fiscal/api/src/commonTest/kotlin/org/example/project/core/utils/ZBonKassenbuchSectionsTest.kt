package org.example.project.core.utils

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A5 — the Z-Bon's Kassenbuch section.
 *
 * The original A5 proposed narrowing this section to Geldtransit and Kassendifferenz only, on the
 * premise that the Ladenkasse holds nothing else. It does not: a supplier paid in cash out of the
 * drawer is an ordinary Auszahlung in the fiscal till that appears nowhere else on the Z-Bon.
 * `an ordinary cash payout is still listed as an expense` is the guard against that regression.
 */
class ZBonKassenbuchSectionsTest {
    private fun entry(
        id: Long,
        type: KassenbuchEntryType,
    ) = KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = id,
        cashBookId = 1L,
        belegNr = "2026-08-000$id",
        entryType = type,
        belegtext = "test",
        belegDatum = "01.08.2026",
        waehrung = "EUR",
        totalAmount = "10.00",
        lineItems = emptyList(),
        currentBalance = "0.00",
        employeeNumber = "EMP001",
        createdAt = "2026-08-01T10:00:00",
    )

    @Test
    fun `transfers land in transit`() {
        val out = entry(1, KassenbuchEntryType.CASH_TRANSFER_OUT)
        val inn = entry(2, KassenbuchEntryType.CASH_TRANSFER_IN)

        val sections = partitionZBonKassenbuch(listOf(out, inn))

        assertEquals(listOf(out, inn), sections.transit)
        assertTrue(sections.income.isEmpty(), "a transfer is not income — it is the same shop's own cash")
        assertTrue(sections.expenses.isEmpty(), "nor an expense; DSFinV-K gives it its own GV_TYP")
    }

    @Test
    fun `surplus and shortage land in differences`() {
        val up = entry(1, KassenbuchEntryType.CASH_SURPLUS)
        val down = entry(2, KassenbuchEntryType.CASH_SHORTAGE)

        val sections = partitionZBonKassenbuch(listOf(up, down))

        assertEquals(listOf(up, down), sections.differences)
        assertTrue(sections.income.isEmpty())
        assertTrue(sections.expenses.isEmpty())
    }

    @Test
    fun `an ordinary cash payout is still listed as an expense`() {
        val payout = entry(1, KassenbuchEntryType.CASH_WITHDRAWAL)

        val sections = partitionZBonKassenbuch(listOf(payout))

        assertEquals(
            listOf(payout),
            sections.expenses,
            "a supplier paid in cash must not vanish from the day's closing document",
        )
        assertTrue(sections.differences.isEmpty())
        assertTrue(sections.transit.isEmpty())
    }

    @Test
    fun `the partition is total - every entry appears exactly once`() {
        val all = KassenbuchEntryType.entries.mapIndexed { i, t -> entry(i.toLong(), t) }

        val sections = partitionZBonKassenbuch(all)

        val placed = sections.transit + sections.differences + sections.income + sections.expenses
        assertEquals(all.size, placed.size, "every entry type must land in exactly one bucket")
        assertEquals(all.toSet(), placed.toSet(), "and no entry may be substituted or dropped")
    }

    @Test
    fun `null and empty inputs yield four empty lists`() {
        listOf(partitionZBonKassenbuch(null), partitionZBonKassenbuch(emptyList())).forEach { sections ->
            assertTrue(
                sections.transit.isEmpty() &&
                    sections.differences.isEmpty() &&
                    sections.income.isEmpty() &&
                    sections.expenses.isEmpty(),
            )
        }
    }
}
