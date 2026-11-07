package org.example.project.features.kassenbuch

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.features.kassenbuch.data.service.BuchungsstapelPeriodWarnings
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A late receipt keeps its Beleg date and books into the open period, so a Buchungsstapel can
 * carry rows dated before the period it was asked for, and — with the backdating override — rows
 * from the previous Wirtschaftsjahr. The file itself stays self-consistent (the header is widened
 * to cover them), but the operator has to be told, because a batch is normally posted into one
 * Wirtschaftsjahr. E2E-2026-09-22 finding R3-1.
 */
class BuchungsstapelPeriodWarningsTest {
    private fun row(belegDate: LocalDate) =
        BuchungsstapelRowInput(
            umsatz = BigDecimal("23.80"),
            belegDate = belegDate,
            belegNr = "2026-09-0029",
            buchungstext = "Wareneinkauf",
            konto = 1000,
            gegenkonto = 3400,
        )

    private val januaryWj = LocalDate.of(2026, 1, 1)

    @Test
    fun `a period whose rows all fall inside it warns about nothing`() {
        val warnings =
            BuchungsstapelPeriodWarnings.detect(
                rows = listOf(row(LocalDate.of(2026, 9, 21)), row(LocalDate.of(2026, 9, 22))),
                requestedStart = LocalDate.of(2026, 9, 1),
                requestedEnd = LocalDate.of(2026, 9, 30),
                wirtschaftsjahrBeginn = januaryWj,
            )

        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a row dated before the requested period is named with its date`() {
        val warnings =
            BuchungsstapelPeriodWarnings.detect(
                rows = listOf(row(LocalDate.of(2026, 8, 13)), row(LocalDate.of(2026, 9, 22))),
                requestedStart = LocalDate.of(2026, 9, 22),
                requestedEnd = LocalDate.of(2026, 9, 22),
                wirtschaftsjahrBeginn = januaryWj,
            )

        assertTrue(warnings.size == 1, "one warning about the period, none about the Wirtschaftsjahr: $warnings")
        assertTrue(warnings.single().contains("13.08.2026"), "must name the earliest Beleg date: ${warnings.single()}")
        assertTrue(warnings.single().contains("1 "), "must count the affected rows: ${warnings.single()}")
    }

    @Test
    fun `rows from two Wirtschaftsjahre are called out separately`() {
        val warnings =
            BuchungsstapelPeriodWarnings.detect(
                rows = listOf(row(LocalDate.of(2025, 9, 21)), row(LocalDate.of(2026, 9, 22))),
                requestedStart = LocalDate.of(2026, 9, 22),
                requestedEnd = LocalDate.of(2026, 9, 22),
                wirtschaftsjahrBeginn = januaryWj,
            )

        assertTrue(warnings.size == 2, "the period warning and the Wirtschaftsjahr warning: $warnings")
        assertTrue(
            warnings.any { it.contains("Wirtschaftsjahr") && it.contains("01.01.2025") && it.contains("01.01.2026") },
            "must name both Wirtschaftsjahre: $warnings",
        )
    }

    @Test
    fun `a Wirtschaftsjahr that starts in July splits on the first of July`() {
        val warnings =
            BuchungsstapelPeriodWarnings.detect(
                rows = listOf(row(LocalDate.of(2026, 6, 30)), row(LocalDate.of(2026, 7, 1))),
                requestedStart = LocalDate.of(2026, 6, 1),
                requestedEnd = LocalDate.of(2026, 7, 31),
                wirtschaftsjahrBeginn = LocalDate.of(2020, 7, 1),
            )

        assertTrue(
            warnings.any { it.contains("01.07.2025") && it.contains("01.07.2026") },
            "the June row belongs to the Wirtschaftsjahr that began 01.07.2025: $warnings",
        )
    }
}
