package org.example.project.admin.features.terminal.presentation

import org.example.project.core.domain.dto.terminal.TerminalDto

/**
 * Which actions the detail panel shows for a terminal, and in which role. The state decides the one
 * primary action so the operator's next step is the loudest control:
 *
 * - fiscal terminal without a TSE client (and active): **Bei TSE registrieren**, then code and edit;
 * - deactivated terminal: **Reaktivieren**, then edit (and the TSE registration if it is still missing);
 * - otherwise: no filled button, an outlined Bearbeiten leads, then the pairing code.
 *
 * [decommission] is the separate "Außerbetriebnahme" section: Deaktivieren (active only), Gerätebindung
 * widerrufen, and TSE-Client abmelden (only when a client is registered; the panel disables it with
 * [tseDeregisterBlockReason] until the terminal is retired). None of this replaces the server guards.
 */
data class TerminalPanelPlan(
    val primary: TerminalPanelAction?,
    val secondary: List<TerminalPanelAction>,
    val decommission: List<TerminalPanelAction>,
) {
    companion object {
        fun of(terminal: TerminalDto.TerminalSummaryDto): TerminalPanelPlan {
            val missing = terminal.isTseMissing()
            val (primary, secondary) =
                when {
                    !terminal.isActive ->
                        TerminalPanelAction.REACTIVATE to
                            listOfNotNull(TerminalPanelAction.REGISTER_TSE.takeIf { missing }, TerminalPanelAction.EDIT)
                    missing ->
                        TerminalPanelAction.REGISTER_TSE to listOf(TerminalPanelAction.GENERATE_CODE, TerminalPanelAction.EDIT)
                    else -> null to listOf(TerminalPanelAction.EDIT, TerminalPanelAction.GENERATE_CODE)
                }
            val decommission =
                buildList {
                    if (terminal.isActive) add(TerminalPanelAction.DEACTIVATE)
                    add(TerminalPanelAction.REVOKE_BINDING)
                    if (terminal.tseClientRegistered) add(TerminalPanelAction.DEREGISTER_TSE)
                }
            return TerminalPanelPlan(primary, secondary, decommission)
        }
    }
}
