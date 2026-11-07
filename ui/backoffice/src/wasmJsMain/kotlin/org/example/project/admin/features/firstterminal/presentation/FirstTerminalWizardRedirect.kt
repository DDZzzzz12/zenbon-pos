package org.example.project.admin.features.firstterminal.presentation

/**
 * True when the first-terminal wizard was opened (typically by a deep link) on a server that
 * already has terminals, so it must hand over to the Terminals screen instead of offering to set up
 * a "first" terminal that is not.
 *
 * Only decided once the probe has answered ([FirstTerminalSetupState.evaluated]) and only at the
 * very start of the flow: a created terminal, or any later step, means the wizard itself made the
 * server non-empty and must run to its pairing code.
 */
fun FirstTerminalSetupState.shouldLeaveWizardForTerminals(): Boolean =
    evaluated &&
        !needsFirstTerminal &&
        step == FirstTerminalStep.CREATE_TERMINAL &&
        createdTerminal == null
