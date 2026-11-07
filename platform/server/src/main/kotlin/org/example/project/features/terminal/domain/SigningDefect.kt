package org.example.project.features.terminal.domain

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.domain.models.Terminal

/**
 * Why a terminal may not TSE-sign, or null when it may. The single definition shared by
 * `TransactionSigningGuard.assertMaySign` (which turns it into its historic exceptions),
 * `ClosingSigner` and `SigningTerminalEligibility`.
 */
enum class SigningDefect {
    INACTIVE,
    NOT_FISCAL,
    NOT_REGISTERED,
    ;

    companion object {
        fun of(
            isActive: Boolean,
            fiscal: Boolean,
            tseClientRegistered: Boolean,
            mode: TseMode,
        ): SigningDefect? =
            when {
                !isActive -> INACTIVE
                mode == TseMode.DISABLED -> null
                !fiscal -> NOT_FISCAL
                mode == TseMode.NORMAL && !tseClientRegistered -> NOT_REGISTERED
                else -> null
            }

        fun of(
            terminal: Terminal,
            mode: TseMode,
        ): SigningDefect? = of(terminal.isActive, terminal.fiscal, terminal.tseClientRegistered, mode)
    }
}
