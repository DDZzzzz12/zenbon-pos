package org.example.project.features.launch.presentation.nav

import org.example.project.core.auth.SessionStatus

/** Everything [resolveDestination] needs. Pure data; no Compose, no coroutines. */
data class LaunchInputs(
    val sessionStatus: SessionStatus = SessionStatus.UNAUTHENTICATED,
    val requiresSetup: Boolean? = null,
    val device: DeviceResolution = DeviceResolution.Checking,
    val offline: Boolean = false,
    /** Set when the station-session exchange was answered with a server-side fault, cleared on retry and on re-pairing. */
    val stationBlocked: StationBlock? = null,
    /** The stored server URL fails ServerUrlPolicy — plain HTTP to a LAN host after the HTTPS switch. */
    val insecureServerUrl: Boolean = false,
    /**
     * The server could not be reached because its TLS certificate is not trusted — the normal state of
     * a fresh till behind Caddy's local CA. Not a network fault: pairing with the certificate check
     * code is what makes the till trust it, so an unpaired device goes to the pairing screen.
     */
    val certificateUntrusted: Boolean = false,
    /**
     * Whether the current session was opened by the station-session exchange rather than by a
     * person signing in — the launch-input mirror of `TokenStore.isStation`, copied in by
     * `LaunchCoordinator` so [resolveDestination] never reads the token store itself.
     *
     * Only an ORDER_STATION consults it, and only because [sessionStatus] alone cannot tell the two
     * apart. A till someone had signed into, whose terminal role is then switched to ORDER_STATION,
     * is AUTHENTICATED as that person; reading that as "already started" resolved it to Main, the
     * one trigger for the exchange never fired, and the device went on presenting the human's JWT
     * indefinitely instead of ever obtaining a station token.
     */
    val stationSession: Boolean = false,
    /**
     * Whether this device holds a pairing token — read locally each time the flow goes [offline] or
     * the server reports [requiresSetup], because in both cases the device check never runs. Only
     * meaningful together with those two: a paired device is never offered a new server address.
     */
    val deviceTokenStored: Boolean = false,
)
