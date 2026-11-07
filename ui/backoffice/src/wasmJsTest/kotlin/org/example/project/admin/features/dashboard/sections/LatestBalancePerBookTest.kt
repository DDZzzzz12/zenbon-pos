package org.example.project.admin.features.dashboard.sections

import org.example.project.admin.features.dashboard.presentation.sections.latestEntryPerBook
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun entry(
    id: Long,
    book: Long,
    balance: String,
) = KassenbuchDto.KassenbuchGroupedSummaryDto(
    headerId = id,
    cashBookId = book,
    belegNr = "B$id",
    entryType = KassenbuchEntryType.entries.first(),
    belegtext = "x",
    belegDatum = "2026-08-28",
    waehrung = "EUR",
    totalAmount = "1.00",
    lineItems = emptyList(),
    currentBalance = balance,
    employeeNumber = "E1",
    createdAt = "2026-08-28T01:00:00",
    currency = "EUR",
)

class LatestBalancePerBookTest {
    @Test
    fun oneLatestEntryPerBookOrderedByBookId() {
        val result =
            latestEntryPerBook(
                listOf(entry(5, 2, "90.00"), entry(3, 1, "10.00"), entry(4, 2, "80.00"), entry(1, 1, "5.00")),
            )
        assertEquals(listOf(1L to "10.00", 2L to "90.00"), result.map { it.cashBookId to it.currentBalance })
    }

    @Test
    fun noEntriesGiveNoLines() {
        assertTrue(latestEntryPerBook(emptyList()).isEmpty())
    }
}
