package org.example.project.admin.features.terminal.presentation

/**
 * The "generate pairing code" dialog, before any code exists.
 *
 * @property availability null while the TSE status is still being read.
 * @property registerTseClient the operator's choice; only honoured when [availability] is selectable.
 */
data class PairingOptionsDraft(
    val terminalId: Long,
    val availability: TseRegistrationOnPairing? = null,
    val registerTseClient: Boolean = false,
) {
    /** What is actually sent: an unavailable registration is never requested. */
    val requestsTseRegistration: Boolean get() = registerTseClient && availability?.selectable == true
}
