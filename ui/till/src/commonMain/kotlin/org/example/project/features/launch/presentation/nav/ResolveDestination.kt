package org.example.project.features.launch.presentation.nav

import org.example.project.core.auth.SessionStatus
import org.example.project.core.domain.enums.terminal.TerminalRole

/**
 * The single source of truth for which screen shows. Pure: same inputs → same destination.
 * Precedence: insecure-server-url → (untrusted certificate, unpaired → pairing) → offline → setup-unknown → setup-required →
 * device (checking/pairing/station-session/role).
 * A device gate always precedes the session gate, so AUTHENTICATED with no/rejected token
 * (terminal unpaired mid-session) resolves to Pairing, not Main.
 * Offline and Setup offer a server-address change only when no device token is stored.
 */
fun resolveDestination(inputs: LaunchInputs): AppDestination =
    when {
        inputs.insecureServerUrl -> AppDestination.InsecureServerUrl
        // An unpaired till that cannot verify the server's certificate gets the pairing screen, where the
        // certificate check code (QR or typed) pins the CA. Offline would be a dead end: it offers no way in.
        inputs.offline && inputs.certificateUntrusted && !inputs.deviceTokenStored -> AppDestination.Pairing
        inputs.offline ->
            AppDestination.Offline(
                canChangeServerAddress = !inputs.deviceTokenStored,
                certificateUntrusted = inputs.certificateUntrusted,
            )
        inputs.requiresSetup == null -> AppDestination.Resolving
        inputs.requiresSetup == true -> AppDestination.Setup(canChangeServerAddress = !inputs.deviceTokenStored)
        else ->
            when (val d = inputs.device) {
                DeviceResolution.Checking -> AppDestination.Resolving
                DeviceResolution.NoToken, DeviceResolution.Rejected -> AppDestination.Pairing
                is DeviceResolution.Paired ->
                    when (d.role) {
                        TerminalRole.CASHIER ->
                            if (inputs.sessionStatus == SessionStatus.AUTHENTICATED) {
                                AppDestination.Main
                            } else {
                                AppDestination.Login
                            }
                        // The exchange IS the station's login: LaunchCoordinator performs it and
                        // marks the session authenticated, and the normal Main mounts.
                        TerminalRole.ORDER_STATION ->
                            when {
                                // BOTH halves are required. AUTHENTICATED alone was the defect: a
                                // till someone had signed into, re-roled to ORDER_STATION, resolved
                                // straight to Main, so the exchange never ran and the device kept
                                // presenting that person's JWT. A person's session on a station is
                                // not a station session — resolve to StationSession instead, and
                                // the exchange replaces it with a station one.
                                inputs.sessionStatus == SessionStatus.AUTHENTICATED && inputs.stationSession ->
                                    AppDestination.Main
                                // The server answered, and answered with its own fault: show the
                                // reason and a retry rather than spinning on the exchange forever.
                                inputs.stationBlocked != null ->
                                    AppDestination.StationBlocked(inputs.stationBlocked)
                                else -> AppDestination.StationSession
                            }
                        else -> AppDestination.RolePlaceholder(d.terminalId, d.role.name)
                    }
            }
    }
