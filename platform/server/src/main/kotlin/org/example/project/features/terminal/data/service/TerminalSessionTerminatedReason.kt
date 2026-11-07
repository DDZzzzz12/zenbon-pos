package org.example.project.features.terminal.data.service

/**
 * Reason carried on the server-pushed [TerminalSessionTerminatedDto] when a terminal's
 * binding or active status changes.
 */
enum class TerminalSessionTerminatedReason {
    REVOKED,
    DEACTIVATED,
    ROLE_CHANGED,
    BROWSER_DISABLED,
}
