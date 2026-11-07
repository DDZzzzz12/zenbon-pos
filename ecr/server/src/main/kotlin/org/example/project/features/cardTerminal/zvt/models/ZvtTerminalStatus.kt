/**
 * Terminal status model for ZVT protocol connection state.
 *
 * Represents the current state of the connection to a payment terminal,
 * including connection status, registration state, and device information.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Current status of the ZVT terminal connection.
 *
 * @property connected Whether a TCP connection to the terminal is active
 * @property registered Whether the ECR has successfully registered with the terminal
 * @property terminalId The terminal ID reported by the device
 * @property softwareVersion Terminal software version (from TLV if available)
 * @property busy Whether the terminal is currently processing a command
 * @property lastErrorCode Last error code received (0x00 if none)
 * @property lastErrorDescription Human-readable description of last error
 */
data class ZvtTerminalStatus(
    val connected: Boolean = false,
    val registered: Boolean = false,
    val terminalId: String? = null,
    val softwareVersion: String? = null,
    val busy: Boolean = false,
    val lastErrorCode: Int = 0,
    val lastErrorDescription: String = "",
)
