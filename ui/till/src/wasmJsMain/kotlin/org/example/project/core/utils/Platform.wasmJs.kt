package org.example.project.core.utils

import kotlinx.browser.window

/** No camera bridge in the preview; the scanner UI hides itself when this is false. */
actual fun isQrScannerAvailable(): Boolean = false

/** Same. Document scanning is an Android capability. */
actual fun isDocumentScannerAvailable(): Boolean = false

/** A browser preview is driven with a keyboard, so numeric entry should assume one. */
@androidx.compose.runtime.Composable
actual fun hasPhysicalKeyboard(): Boolean = true

actual fun openUrlInBrowser(url: String) {
    window.open(url, "_blank")
}

/**
 * Instant-tap mode is an Android kiosk affordance (immediate touch feedback without the
 * long-press delay). A browser has no equivalent and needs none.
 */
actual fun requestInstantTapMode() = Unit

actual fun releaseInstantTapMode() = Unit

/**
 * Deliberately does nothing. A web page cannot power off its host, and pretending to — by closing
 * the tab, say — would make an operator think the till shut down when it did not.
 */
actual fun shutdownSystem() = Unit

/**
 * Deliberately does nothing. A tab a page opened itself can be closed; this one was not, so
 * `window.close()` would be silently ignored and the exit dialog would appear to hang.
 */
actual fun exitApplication() = Unit
