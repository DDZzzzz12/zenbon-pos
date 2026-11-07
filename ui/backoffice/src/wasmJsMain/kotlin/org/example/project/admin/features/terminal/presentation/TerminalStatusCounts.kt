package org.example.project.admin.features.terminal.presentation

import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.core.domain.dto.terminal.TerminalDto

/** A fiscal terminal that is not registered at the TSE: it signs sales but has no TSE client. */
fun TerminalDto.TerminalSummaryDto.isTseMissing(): Boolean = fiscal && !tseClientRegistered

/** A pairing code was issued and has not been consumed or cleared yet. */
fun TerminalDto.TerminalSummaryDto.isPairingOpen(): Boolean = pairingCodeExpiresAt != null

/**
 * The counters of the Terminals status strip. They double as filters, so [countFor] is exactly the
 * number of rows `TerminalsState.Data.visible` shows for that filter before a search narrows it.
 */
data class TerminalStatusCounts(
    val all: Int,
    val active: Int,
    val tseMissing: Int,
    val pairingOpen: Int,
    val inactive: Int,
) {
    fun countFor(filter: TerminalFilter): Int =
        when (filter) {
            TerminalFilter.ALL -> all
            TerminalFilter.ACTIVE -> active
            TerminalFilter.TSE_MISSING -> tseMissing
            TerminalFilter.PAIRING_OPEN -> pairingOpen
            TerminalFilter.INACTIVE -> inactive
        }

    companion object {
        /** Tiles on a phone: "Inaktiv" is reached through the Filter button there. */
        val COMPACT_TILES: List<TerminalFilter> = TerminalFilter.entries.filter { it != TerminalFilter.INACTIVE }

        fun of(terminals: List<TerminalDto.TerminalSummaryDto>): TerminalStatusCounts =
            TerminalStatusCounts(
                all = terminals.size,
                active = terminals.count { it.isActive },
                tseMissing = terminals.count { it.isTseMissing() },
                pairingOpen = terminals.count { it.isPairingOpen() },
                inactive = terminals.count { !it.isActive },
            )
    }
}
