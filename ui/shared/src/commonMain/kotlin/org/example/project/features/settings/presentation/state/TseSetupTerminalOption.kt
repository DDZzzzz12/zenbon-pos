package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.terminal.TerminalDto

/**
 * A terminal the TSE setup may register as the device's first client.
 *
 * Only active fiscal terminals qualify — the server refuses any other — and a terminal need not
 * be paired yet: a client id is only a name registered on the TSE. That is what lets the back
 * office, which is no terminal itself, commission the TSE (owner decision 2026-10-04).
 *
 * @property clientId The id sent as `terminalId` and registered on the TSE (e.g. "TER-001")
 * @property name The operator's name for it, when it differs from the id
 */
data class TseSetupTerminalOption(
    val clientId: String,
    val name: String?,
) {
    /** "Bar (TER-001)", or just the id when the terminal carries no name of its own. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() && it != clientId }?.let { "$it ($clientId)" } ?: clientId

    companion object {
        /** The terminals setup can use, in the order the server lists them. */
        fun eligible(terminals: List<TerminalDto.TerminalSummaryDto>): List<TseSetupTerminalOption> =
            terminals
                .filter { it.isActive && it.fiscal }
                .map { TseSetupTerminalOption(clientId = it.terminalId, name = it.name) }

        /**
         * The registers a filtered TSE export can be restricted to: every terminal whose client is
         * registered on the TSE — a deactivated one too, since its transactions are still there.
         */
        fun exportable(terminals: List<TerminalDto.TerminalSummaryDto>): List<TseSetupTerminalOption> =
            terminals
                .filter { it.tseClientRegistered }
                .map { TseSetupTerminalOption(clientId = it.terminalId, name = it.name) }

        /** The caller's own terminal when it is eligible, else the first one; null when none is. */
        fun preselect(
            options: List<TseSetupTerminalOption>,
            ownClientId: String?,
        ): String? = options.firstOrNull { it.clientId == ownClientId }?.clientId ?: options.firstOrNull()?.clientId
    }
}
