package org.example.project.features.tse.findings

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseFindingKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TanrContinuityAnalyzerTest {
    private val start = LocalDateTime.parse("2026-09-01T00:00:00")
    private val end = LocalDateTime.parse("2026-10-01T00:00:00")
    private val inside = LocalDateTime.parse("2026-09-21T11:00:00")
    private val before = LocalDateTime.parse("2026-08-31T23:00:00")

    private fun row(
        tanr: Long,
        at: LocalDateTime = inside,
        serial: String = "AA",
    ) = TanrRow(serial, tanr, at)

    @Test
    fun `a hole between two recorded TANRs is a gap`() {
        val findings = TanrContinuityAnalyzer.analyze(listOf(row(1), row(2), row(6)), start, end)
        assertEquals(listOf(TanrFinding("AA", 3, 5, TseFindingKind.GAP)), findings)
    }

    @Test
    fun `a gap belongs to the period of the row after it`() {
        val findings = TanrContinuityAnalyzer.analyze(listOf(row(1, before), row(4, before)), start, end)
        assertEquals(emptyList(), findings)
    }

    @Test
    fun `numbers before the first recorded TANR are not a gap`() {
        assertEquals(emptyList(), TanrContinuityAnalyzer.analyze(listOf(row(7), row(8)), start, end))
    }

    @Test
    fun `the same TANR twice is a duplicate`() {
        val findings = TanrContinuityAnalyzer.analyze(listOf(row(1), row(2), row(2)), start, end)
        assertEquals(listOf(TanrFinding("AA", 2, 2, TseFindingKind.DUPLICATE)), findings)
    }

    @Test
    fun `devices are analysed separately`() {
        val findings = TanrContinuityAnalyzer.analyze(listOf(row(1, serial = "AA"), row(1, serial = "BB"), row(3, serial = "BB")), start, end)
        assertEquals(listOf(TanrFinding("BB", 2, 2, TseFindingKind.GAP)), findings)
    }
}
