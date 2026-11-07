package org.example.project.features.launch.presentation.nav

import org.example.project.core.domain.enums.terminal.TerminalRole

/** Result of validating the stored device token against the server. */
sealed interface DeviceResolution {
    data object Checking : DeviceResolution

    data object NoToken : DeviceResolution

    data object Rejected : DeviceResolution

    data class Paired(
        val terminalId: String,
        val role: TerminalRole,
    ) : DeviceResolution
}
