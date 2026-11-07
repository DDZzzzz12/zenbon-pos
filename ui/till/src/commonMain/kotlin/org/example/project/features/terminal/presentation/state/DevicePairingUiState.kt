package org.example.project.features.terminal.presentation.state

import org.example.project.core.presentation.error.UiError

/** UI state for the device-pairing screen. */
data class DevicePairingUiState(
    val code: String = "",
    /** Optional certificate check code ("Prüfcode") typed by the operator; blank when none is needed. */
    val rootCode: String = "",
    val isPairing: Boolean = false,
    val error: UiError? = null,
    /** True when the server URL has never been configured and the settings dialog should open. */
    val showUrlDialog: Boolean = false,
)
