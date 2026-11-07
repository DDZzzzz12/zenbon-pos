package org.example.project.features.launch.presentation

/**
 * What a terminal-auth failure invalidates, as requested through [TerminalColdResetBus].
 *
 * The distinction is what keeps a till in service: an employee session can expire for reasons that
 * say nothing about the hardware, and unpairing on one of those sends a technician out with a
 * pairing code for what a login would have fixed (E2E-2026-09-19). Each COLD value names a cause
 * the SERVER gave, so no caller can request an unpair on a locally-inferred error.
 */
enum class TerminalResetKind(
    val coldResetReason: TerminalColdResetReason?,
) {
    /** Only the employee session ended: back to the login screen, pairing intact. */
    SESSION(null),

    /** The server refused this device token (409/403): the pairing itself is dead. */
    COLD_DEVICE_REJECTED(TerminalColdResetReason.DEVICE_TOKEN_REJECTED),

    /** The operator asked to re-pair this device. */
    COLD_OPERATOR_REPAIR(TerminalColdResetReason.OPERATOR_REPAIR),
}
