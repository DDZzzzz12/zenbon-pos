package org.example.project.core.utils

/**
 * Shared constants for the client device identity sent to the server.
 *
 * The client attaches its device id via the [DEVICE_ID_HEADER] header. The
 * server bakes it into the JWT (`deviceId` claim) at login and matches it
 * against registered terminals for fiscal flows (TSE, bills, orders).
 *
 * The wasm admin console is a browser, not a fiscal terminal. It uses a
 * synthetic per-browser id prefixed with [ADMIN_WEB_PREFIX] so the server can
 * recognise and reject any attempt to register it as a terminal.
 */
object DeviceIdentifiers {
    /** HTTP header carrying the client device id. */
    const val DEVICE_ID_HEADER = "X-Device-ID"

    /**
     * HTTP header carrying the paired-device credential read from
     * [org.example.project.core.device.DeviceTokenProvider] at request time. Omitted while the
     * device is unpaired.
     */
    const val DEVICE_TOKEN_HEADER = "X-Device-Token"

    /**
     * HTTP header carrying the client's build version. The till sends it when it opens its
     * notification stream, so the server can keep the terminal's DSFinV-K `TERMINAL_SW_VERSION`
     * current after an app update.
     */
    const val APP_VERSION_HEADER = "X-App-Version"

    /** Prefix marking a synthetic device id minted by the wasm admin console. */
    const val ADMIN_WEB_PREFIX = "admin-web-"

    /**
     * Prefix marking a synthetic device id minted by the composeApp browser preview.
     *
     * Distinct from [ADMIN_WEB_PREFIX] because the two are different clients with different
     * rights: the admin console is a back-office tool that must never be a terminal, while the
     * preview is the till itself and registers as one. Both run as wasm in a browser and are
     * served from the same origin, so they must also not share a stored id.
     */
    const val POS_PREVIEW_PREFIX = "pos-preview-"

    /**
     * True when [deviceId] belongs to the wasm admin console. Such clients can
     * never be a fiscal terminal and must be rejected from terminal
     * registration and TSE-signing operations.
     */
    fun isAdminWebDevice(deviceId: String): Boolean = deviceId.startsWith(ADMIN_WEB_PREFIX)

    /** True when [deviceId] belongs to the composeApp browser preview. */
    fun isPosPreviewDevice(deviceId: String): Boolean = deviceId.startsWith(POS_PREVIEW_PREFIX)
}
