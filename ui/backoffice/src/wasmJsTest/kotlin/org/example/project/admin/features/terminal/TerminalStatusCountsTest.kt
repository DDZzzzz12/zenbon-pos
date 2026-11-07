package org.example.project.admin.features.terminal

import org.example.project.admin.features.terminal.TerminalTestFixtures.summary
import org.example.project.admin.features.terminal.presentation.TerminalStatusCounts
import org.example.project.admin.features.terminal.presentation.TerminalTseState
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.admin.features.terminal.presentation.isTseMissing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalStatusCountsTest {
    private val terminals =
        listOf(
            summary(1, tse = true),
            summary(2, tse = false, fiscal = true),
            summary(3, tse = false, fiscal = false),
            summary(4, active = false, tse = true),
            summary(5, pairingCodeExpiresAt = "2026-07-26T10:05:00Z", tse = true),
        )

    @Test
    fun countsAreOverEveryTerminal() {
        val counts = TerminalStatusCounts.of(terminals)
        assertEquals(5, counts.all)
        assertEquals(4, counts.active)
        assertEquals(1, counts.inactive)
        assertEquals(1, counts.tseMissing)
        assertEquals(1, counts.pairingOpen)
    }

    @Test
    fun tseMissingIsFiscalAndNotRegisteredOnly() {
        assertTrue(summary(1, tse = false, fiscal = true).isTseMissing())
        assertFalse(summary(1, tse = false, fiscal = false).isTseMissing())
        assertFalse(summary(1, tse = true, fiscal = true).isTseMissing())
    }

    @Test
    fun tseStateDistinguishesTheThreeWords() {
        assertEquals(TerminalTseState.REGISTERED, TerminalTseState.of(summary(1, tse = true)))
        assertEquals(TerminalTseState.MISSING, TerminalTseState.of(summary(1, tse = false, fiscal = true)))
        assertEquals(TerminalTseState.NOT_FISCAL, TerminalTseState.of(summary(1, tse = false, fiscal = false)))
    }

    @Test
    fun everyTileCountMatchesTheRowsItsFilterShows() {
        val counts = TerminalStatusCounts.of(terminals)
        TerminalFilter.entries.forEach { filter ->
            val shown = TerminalsState.Data(all = terminals, filter = filter).visible.size
            assertEquals(shown, counts.countFor(filter), "tile for $filter")
        }
    }

    @Test
    fun theTseMissingFilterKeepsOnlyThoseTerminals() {
        val visible = TerminalsState.Data(all = terminals, filter = TerminalFilter.TSE_MISSING).visible
        assertEquals(listOf(2L), visible.map { it.id })
    }

    @Test
    fun theStripKeepsItsOrderAndThePhoneDropsInactive() {
        assertEquals(
            listOf(TerminalFilter.ALL, TerminalFilter.ACTIVE, TerminalFilter.TSE_MISSING, TerminalFilter.PAIRING_OPEN, TerminalFilter.INACTIVE),
            TerminalFilter.entries,
        )
        assertEquals(
            listOf(TerminalFilter.ALL, TerminalFilter.ACTIVE, TerminalFilter.TSE_MISSING, TerminalFilter.PAIRING_OPEN),
            TerminalStatusCounts.COMPACT_TILES,
        )
    }

    @Test
    fun theCountsIgnoreSearchAndFilter() {
        val data = TerminalsState.Data(all = terminals, search = "T001", filter = TerminalFilter.INACTIVE)
        assertEquals(TerminalStatusCounts.of(terminals), data.statusCounts)
    }
}
