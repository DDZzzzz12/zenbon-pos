package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Localization keys for the composeApp launch gate (setup check + device-token validation). */
object LaunchKeys {
    val checkingTitle = StringKey("launch.checking_title")
    val checkingSubtitle = StringKey("launch.checking_subtitle")
    val retryButton = StringKey("launch.retry_button")
    val offlineTitle = StringKey("launch.offline_title")
    val offlineSubtitle = StringKey("launch.offline_subtitle")
    val stationRefusedTitle = StringKey("launch.station_refused_title")

    /** Shown when the server refuses the station session without writing a detail of its own. */
    val stationRefusedMessage = StringKey("launch.station_refused_message")

    /** Shown when the exchange succeeded but the station token cannot be decoded. */
    val stationTokenUnreadable = StringKey("launch.station_token_unreadable")

    /**
     * Title for a station that cannot start *yet* — as opposed to one the server refuses.
     *
     * The device stays paired and the screen keeps its retry: the fault is on the server side, or
     * in this device's key store, and the same device works the moment it is fixed, so re-pairing
     * would be the wrong instruction.
     */
    val stationBlockedTitle = StringKey("launch.station_blocked_title")

    /** The station's TSE client is not registered while the server signs to hardware. */
    val stationTseNotRegistered = StringKey("launch.station_tse_not_registered")

    /** The shared terminal account the station runs under is missing from this installation. */
    val stationServerIncomplete = StringKey("launch.station_server_incomplete")

    /** The server issued the station session, but this device's key store cannot seal it right now. */
    val stationStorageUnavailable = StringKey("launch.station_storage_unavailable")

    /**
     * The nav footer's leave action on an order station, where logout has no meaning.
     *
     * A station has no person signed in: leaving it means handing the terminal back to pairing, so
     * the label names that instead of "Logout".
     */
    val repairDevice = StringKey("launch.repair_device")

    /**
     * The nav footer's back-office action, on the till AND on an order station.
     *
     * Deliberately ungated: the web admin enforces its own login, so an anonymous helper at a
     * station reaches only its login page — and hiding the action would push staff to type the
     * server address by hand instead.
     */
    val openBackoffice = StringKey("launch.open_backoffice")

    /** Shown when the platform cannot hand a URL to a browser (none installed, kiosk lock-task). */
    val backofficeOpenFailed = StringKey("launch.backoffice_open_failed")

    /** Label above the address the offline launch screen could not reach. */
    val serverAddressLabel = StringKey("launch.server_address_label")

    /** The offline launch screen's action for a device that is not paired yet. */
    val changeServerAddress = StringKey("launch.change_server_address")

    /**
     * Why a paired device offers no server-address change: shown on the offline launch screen in
     * place of the action, and as the message when the change is refused anyway.
     */
    val serverAddressLockedWhilePaired = StringKey("launch.server_address_locked_while_paired")

    val serverAddressErrorTitle = StringKey("launch.server_address_error_title")

    /** The typed address is not `http(s)://host[:port]` — nothing was stored. */
    val serverAddressInvalid = StringKey("launch.server_address_invalid")
}
