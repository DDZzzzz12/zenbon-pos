package org.example.project.admin.features.terminal.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.StringKey

/**
 * Whether a pairing code may ask the device's pairing to register the terminal's TSE client, and
 * when not, the hint the dialog shows next to its disabled box. The server refuses the same cases
 * (409), so this only keeps the operator from asking for something that would be refused.
 */
enum class TseRegistrationOnPairing(
    val hintKey: StringKey,
) {
    AVAILABLE(AdminStringKeys.TerminalPairingRegisterTseHint),
    NOT_FISCAL(AdminStringKeys.TerminalPairingTseNotFiscal),
    ALREADY_REGISTERED(AdminStringKeys.TerminalPairingTseAlreadyRegistered),
    TSE_NOT_READY(AdminStringKeys.TerminalPairingTseNotReady),
    STATUS_UNKNOWN(AdminStringKeys.TerminalPairingTseStatusUnknown),
    ;

    /** Only an available registration may be ticked; it is also the default then. */
    val selectable: Boolean get() = this == AVAILABLE

    companion object {
        /** [status] null = the TSE pairing status could not be read. */
        fun of(
            fiscal: Boolean,
            tseClientRegistered: Boolean,
            status: TerminalDto.TsePairingStatusDto?,
        ): TseRegistrationOnPairing =
            when {
                !fiscal -> NOT_FISCAL
                tseClientRegistered -> ALREADY_REGISTERED
                status == null -> STATUS_UNKNOWN
                !status.available -> TSE_NOT_READY
                else -> AVAILABLE
            }
    }
}
