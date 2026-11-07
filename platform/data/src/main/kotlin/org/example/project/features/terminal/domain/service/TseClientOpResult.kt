package org.example.project.features.terminal.domain.service

/**
 * Outcome of a TSE client registration or deregistration as [TerminalFiscalGateway] reports it:
 * whether the device accepted the operation, and the device's own message, which the terminal
 * service quotes in its refusal when [success] is false.
 */
data class TseClientOpResult(
    val success: Boolean,
    val message: String?,
)
