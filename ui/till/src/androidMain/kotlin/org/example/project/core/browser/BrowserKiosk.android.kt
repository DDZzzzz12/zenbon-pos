package org.example.project.core.browser

actual object BrowserKiosk {
    actual fun install() = Unit

    actual fun setLeaveGuard(on: Boolean) = Unit

    actual fun requestFullscreenOnFirstGesture() = Unit

    actual fun consumePairingCodeFromUrl(): String? = null

    actual fun isInsecureOrigin(): Boolean = false
}
