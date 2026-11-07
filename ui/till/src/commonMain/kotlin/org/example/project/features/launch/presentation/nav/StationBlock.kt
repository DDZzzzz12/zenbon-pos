package org.example.project.features.launch.presentation.nav

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys

/**
 * Why a paired order station could not open its session, when the server said so and the device is
 * *not* at fault, or this device cannot store the session right now; the pairing is intact.
 *
 * Distinct from a refusal: a refusal (revoked, deactivated, no shell for the role) cold-resets the
 * device back to pairing, because the pairing itself is what is wrong. A block leaves the device
 * paired — the same terminal starts normally once someone fixes the server side, or the key store
 * answers again — so the launch screen keeps a retry and the operator is told what to have fixed
 * instead of being sent to re-pair a device that is already correctly paired.
 */
enum class StationBlock(
    val messageKey: StringKey,
) {
    /** The server signs to hardware and this terminal has no registered TSE client yet. */
    TSE_CLIENT_NOT_REGISTERED(LaunchKeys.stationTseNotRegistered),

    /** The shared terminal-device account every station runs under is not provisioned. */
    SERVER_INSTALLATION_INCOMPLETE(LaunchKeys.stationServerIncomplete),

    /**
     * The shop has not enabled browser stations for this role. The pairing is intact and the
     * block clears by itself once a manager enables them (the re-check re-runs the exchange).
     */
    BROWSER_STATIONS_DISABLED(TerminalPairingKeys.browserStationDisabled),

    /**
     * The server issued the session, but sealing it failed: the Keystore or keychain is unusable right now. A
     * transient key-store failure must never destroy a pairing, so this blocks instead of refusing.
     */
    LOCAL_STORAGE_UNAVAILABLE(LaunchKeys.stationStorageUnavailable),
}
