package org.example.project.core.domain.dto.kassenbuch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the transfer request's money and date validation.
 *
 * The amount check is the interesting one: it is the client-side half of a two-level guard
 * (CLAUDE.md rule 8) on a value that moves cash between two books, so a sign or precision slip
 * here would propose a transfer that means something other than what the operator typed.
 */
class CashBookDtoValidationTest {
    private fun transfer(
        amount: String = "1200.00",
        fromBookId: Long = 1L,
        toBookId: Long = 2L,
        belegDatum: String = "19.07.2026",
    ) = CashBookDto.CreateTransferRequestDto(
        fromBookId = fromBookId,
        toBookId = toBookId,
        amount = amount,
        belegDatum = belegDatum,
    )

    @Test
    fun `a well formed transfer validates`() {
        assertEquals(emptyList(), transfer().validate())
    }

    @Test
    fun `amount accepts up to four decimals, matching the DECIMAL(19,4) column`() {
        assertEquals(emptyList(), transfer(amount = "0.0001").validate())
        assertEquals(emptyList(), transfer(amount = "1200").validate())
    }

    @Test
    fun `amount rejects a negative, which would silently flip the transfer direction`() {
        assertTrue(transfer(amount = "-1200.00").validate().any { it.contains("amount") })
    }

    @Test
    fun `amount rejects zero in every spelling`() {
        listOf("0", "0.00", "0.0000").forEach { zero ->
            assertTrue(
                transfer(amount = zero).validate().any { it.contains("amount") },
                "moving nothing is not a transfer, but '$zero' was accepted",
            )
        }
    }

    @Test
    fun `amount rejects non-numeric and over-precise values`() {
        listOf("", " ", "1,200.00", "1.23456", "abc", "1e3").forEach { bad ->
            assertTrue(
                transfer(amount = bad).validate().any { it.contains("amount") },
                "'$bad' is not a money string but was accepted",
            )
        }
    }

    @Test
    fun `a transfer to the same book is rejected`() {
        assertTrue(
            transfer(fromBookId = 7L, toBookId = 7L).validate().any { it.contains("differ") },
        )
    }

    @Test
    fun `belegDatum must be DD_MM_YYYY`() {
        listOf("2026-07-19", "9.7.2026", "19/07/2026", "").forEach { bad ->
            assertTrue(
                transfer(belegDatum = bad).validate().any { it.contains("belegDatum") },
                "'$bad' is not DD.MM.YYYY but was accepted",
            )
        }
    }
}
