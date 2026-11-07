package org.example.project.admin.features.terminal.presentation

import org.example.project.core.domain.dto.terminal.TerminalDto

/** What the TSE column says about a terminal. "Nicht registriert" is only a warning for a fiscal terminal. */
enum class TerminalTseState {
    REGISTERED,
    MISSING,
    NOT_FISCAL,
    ;

    companion object {
        fun of(terminal: TerminalDto.TerminalSummaryDto): TerminalTseState =
            when {
                terminal.tseClientRegistered -> REGISTERED
                terminal.fiscal -> MISSING
                else -> NOT_FISCAL
            }
    }
}
