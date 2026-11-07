package org.example.project.admin.features.terminal.presentation

/** The three confirm dialogs the detail panel can ask the screen to open for the selected terminal. */
class TerminalDialogRequests(
    val onDeregister: () -> Unit,
    val onRevoke: () -> Unit,
    val onDeactivate: () -> Unit,
)
