package org.example.project.core.browser

/**
 * Page-level hardening for a browser station. Native clients have no page, so every member is a
 * no-op there ([isInsecureOrigin] is false).
 */
expect object BrowserKiosk {
    /** Traps the back button and stops overscroll bounce. Call once, before the UI mounts. */
    fun install()

    /** Asks the browser to confirm before the tab is closed or reloaded. */
    fun setLeaveGuard(on: Boolean)

    /** With `?kiosk=1` in the URL, enters fullscreen on the first pointer press. */
    fun requestFullscreenOnFirstGesture()

    /**
     * Reads a `?code=<6 digits>` pairing code from the address bar and removes that parameter from it
     * (the code is single-use and must not linger in history or bookmarks). Call before [install], or
     * the back-trap history entry keeps the code. Null on native clients and when absent or malformed.
     */
    fun consumePairingCodeFromUrl(): String?

    /** True when the page is neither https nor loopback, so the device token travels in clear text. */
    fun isInsecureOrigin(): Boolean
}
