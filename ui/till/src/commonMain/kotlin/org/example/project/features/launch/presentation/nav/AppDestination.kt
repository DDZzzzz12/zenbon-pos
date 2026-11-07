package org.example.project.features.launch.presentation.nav

/** The single top-level destination the launch/auth flow can be in. */
sealed interface AppDestination {
    data object Resolving : AppDestination

    /**
     * The server could not be reached. [canChangeServerAddress] is true only for a device that is
     * not paired: a fresh install starts on the platform default address and needs a way to be
     * pointed at the real server, while a paired device's token belongs to the server that issued
     * it (see `LaunchCoordinator.changeServerAddress`).
     */
    data class Offline(
        val canChangeServerAddress: Boolean,
        /**
         * The server answered the TLS handshake but its certificate is not trusted on this device — a till
         * that was paired before the server moved behind Caddy's local CA. Not a network fault: the screen
         * offers the certificate check code, which pins the CA without un-pairing.
         */
        val certificateUntrusted: Boolean = false,
    ) : AppDestination

    /**
     * The stored server address is plain HTTP to a LAN host. Not [Offline]: retrying can never work,
     * because the server no longer listens there — staff must enter the https:// address.
     */
    data object InsecureServerUrl : AppDestination

    /**
     * The server answered that it has not been set up. [canChangeServerAddress] follows the same
     * rule as [Offline]: a device may be pointed at another server only while it is not paired — a
     * paired till reaches this screen when its own server was reset, and its token still must not
     * go to a typed address.
     */
    data class Setup(
        val canChangeServerAddress: Boolean,
    ) : AppDestination

    data object Pairing : AppDestination

    /** A paired ORDER_STATION exchanging its device token for a station JWT; the launch screen shows while it runs. */
    data object StationSession : AppDestination

    /**
     * A paired order station the server cannot start yet (see [StationBlock]). Shows the reason and
     * a retry; the device stays paired, so this is deliberately not [Pairing] — and it is not
     * [Offline] either, because the server answered.
     */
    data class StationBlocked(
        val block: StationBlock,
    ) : AppDestination

    data class RolePlaceholder(
        val terminalId: String,
        val role: String,
    ) : AppDestination

    data object Login : AppDestination

    data object Main : AppDestination
}
