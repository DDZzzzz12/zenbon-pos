package org.example.project

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.example.project.core.browser.BrowserKiosk
import org.example.project.core.browser.PendingPairingCode
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.device.WasmClientKind
import org.example.project.core.utils.LanguageProvider
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.di.initKoin

/**
 * The browser preview's entry point.
 *
 * Mirrors the desktop `Main.kt`: start Koin, provide the window-size and language composition
 * locals the whole UI reads, then show [App]. There is no database to open first and no touch
 * interceptor to install, which is the whole of the difference.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Must precede initKoin: the HTTP client reads the device id when it builds, and this page is
    // served from the same origin as the admin console, so the identity has to be declared before
    // anything can mistake this till for that back-office tool.
    DeviceInfoProvider.configureClientKind(WasmClientKind.TILL_PREVIEW)
    initKoin()
    // Read and strip the pairing code BEFORE install(): its back-trap history entry copies the URL.
    PendingPairingCode.set(BrowserKiosk.consumePairingCodeFromUrl())
    BrowserKiosk.install()
    BrowserKiosk.requestFullscreenOnFirstGesture()
    ComposeViewport(document.getElementById("ComposeTarget")!!) {
        ProvideWindowSizeClass {
            LanguageProvider {
                App()
            }
        }
    }
}
