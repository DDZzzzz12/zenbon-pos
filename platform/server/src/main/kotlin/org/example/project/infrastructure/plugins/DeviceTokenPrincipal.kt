package org.example.project.infrastructure.plugins

import org.example.project.features.terminal.data.service.TerminalCapability

/**
 * Principal for a login-less order station authenticated via the `X-Device-Token` header
 * (Terminal Phase A4, Task 5). Carries the resolved [TerminalCapability] so [getTokenClaims] can
 * synthesize claims attributed to the shared terminal-employee (username "terminal-device").
 */
data class DeviceTokenPrincipal(
    val cap: TerminalCapability,
)
