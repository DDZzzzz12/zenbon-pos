package org.example.project.features.monthlyclose.checks

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDetailCodes
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.features.monthlyclose.data.service.checks.BillsOutsideZBonCheck
import org.example.project.features.monthlyclose.data.service.checks.ChainIntegrityCheck
import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * admin16 NOTE-1: every checklist detail now travels as a code the client localizes. A code the
 * server emits but `MonthlyCloseDetailCodes.ALL` does not list would escape the client's
 * every-code-is-translated guard, so this pins that each check source only names listed codes.
 */
class ChecklistDetailCodeTest {
    // The checks live in :fiscal-server since the fiscal server move (stage B2-3); the test stays with the host fixtures.
    private val checksDir =
        File(SourceRoots.repoRoot, "fiscal/server/src/main/kotlin/org/example/project/features/monthlyclose/data/service/checks")

    @Test
    fun `every detail code a check names is one the client is guarded to translate`() {
        val referenced =
            checksDir
                .listFiles { f -> f.extension == "kt" }!!
                .flatMap { Regex("""MonthlyCloseDetailCodes\.([A-Z_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
                .filterNot { it.startsWith("EXPORT_") }
                .toSet()
        val listed =
            MonthlyCloseDetailCodes::class
                .java.declaredFields
                .filter { it.type == String::class.java && !it.name.startsWith("EXPORT_") }
                .map { it.name }
                .toSet()
        assertTrue(referenced.isNotEmpty(), "no check references a detail code — wrong directory?")
        assertTrue(listed.containsAll(referenced), "codes named by checks but not declared: ${referenced - listed}")
        assertEquals(listed.size, MonthlyCloseDetailCodes.ALL.size, "a declared code is missing from ALL")
    }

    @Test
    fun `every check that reports a problem carries a detail code from ALL`() {
        val overlapping =
            MonthlyCloseDto.ZBonRollupDto(
                rows = listOf(row("2026-05-01T00:00", "2026-05-02T12:00"), row("2026-05-02T08:00", "2026-05-03T00:00")),
                totals = row("2026-05-01T00:00", "2026-05-03T00:00"),
                chainGaps =
                    listOf(
                        MonthlyCloseDto.ZBonChainGapDto(afterZBonId = 1L, gapStart = "a", gapEnd = "b", hasBillsInGap = true),
                    ),
            )
        val results = listOf(ChainIntegrityCheck().run(overlapping), BillsOutsideZBonCheck().run(overlapping))
        results.forEach { result ->
            assertTrue(result.detailCode in MonthlyCloseDetailCodes.ALL, "${result.id}: ${result.detailCode}")
            assertEquals(listOf("1"), result.detailArgs, result.id)
        }
    }

    private fun row(
        start: String,
        end: String,
    ) = MonthlyCloseDto.ZBonRollupRowDto(
        zBonId = 1L,
        zBonNumber = "1",
        startTime = start,
        endTime = end,
        grossRevenue = "0.00",
        netRevenue = "0.00",
        vatTotal = "0.00",
        tips = "0.00",
        refunds = "0.00",
        cancellations = "0.00",
        paymentMethodTotals = emptyList(),
    )
}
