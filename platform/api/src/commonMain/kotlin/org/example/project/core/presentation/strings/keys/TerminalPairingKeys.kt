package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Localization keys for the new-device pairing screen (Terminal Phase RB1). */
object TerminalPairingKeys {
    val title = StringKey("terminal_pairing.title")
    val subtitle = StringKey("terminal_pairing.subtitle")
    val codeLabel = StringKey("terminal_pairing.code_label")
    val pairButton = StringKey("terminal_pairing.pair_button")
    val scanQr = StringKey("terminal_pairing.scan_qr")
    val errorEmptyCode = StringKey("terminal_pairing.error_empty_code")
    val errorCodeInvalid = StringKey("terminal_pairing.error_code_invalid")
    val errorCodeExpired = StringKey("terminal_pairing.error_code_expired")
    val errorTseRegistrationFailed = StringKey("terminal_pairing.error_tse_registration_failed")
    val errorQrInvalid = StringKey("terminal_pairing.error_qr_invalid")

    /** The server paired the device, but this device could not store the pairing; the code is used up. */
    val errorStorageFailed = StringKey("terminal_pairing.error_storage_failed")
    val currentServerUrl = StringKey("terminal_pairing.current_server_url")
    val changeServerUrl = StringKey("terminal_pairing.change_server_url")
    val browserStationDisabled = StringKey("terminal_pairing.browser_station_disabled")
    val insecureConnection = StringKey("terminal_pairing.insecure_connection")
    val rootCodeLabel = StringKey("terminal_pairing.root_code_label")
    val rootCodeHint = StringKey("terminal_pairing.root_code_hint")
    val errorRootCodeInvalid = StringKey("terminal_pairing.error_root_code_invalid")
    val errorRootMismatch = StringKey("terminal_pairing.error_root_mismatch")
    val errorRootFetchFailed = StringKey("terminal_pairing.error_root_fetch_failed")
    val trustCertificateButton = StringKey("terminal_pairing.trust_certificate_button")
    val untrustedCertificateHint = StringKey("terminal_pairing.untrusted_certificate_hint")
}
