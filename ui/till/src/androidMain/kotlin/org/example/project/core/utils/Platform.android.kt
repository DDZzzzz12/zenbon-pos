package org.example.project.core.utils

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import org.koin.java.KoinJavaComponent.get
import kotlin.system.exitProcess

// Android platform detection: the `isDesktop()` actual now sits beside its expect in shared-ui.

/**
 * QR scanner is available on Android via camera hardware.
 */
actual fun isQrScannerAvailable(): Boolean = true

/**
 * Document scanner is available on Android via ML Kit (requires Google Play Services).
 */
actual fun isDocumentScannerAvailable(): Boolean = true

/**
 * Detects whether a physical (hardware) keyboard is attached on Android.
 * Returns true when a USB or Bluetooth keyboard is connected.
 */
@androidx.compose.runtime.Composable
actual fun hasPhysicalKeyboard(): Boolean {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
}

actual fun requestInstantTapMode() { /* no-op on Android */ }

actual fun releaseInstantTapMode() { /* no-op on Android */ }

/**
 * No-op on Android. Shutdown button is only shown on desktop.
 */
actual fun shutdownSystem() { /* no-op */ }

/**
 * Ends the app's process, matching what the exit dialog did before this was an `expect`.
 */
actual fun exitApplication() {
    exitProcess(0)
}

/**
 * Opens a URL in the system's default browser on Android.
 */
actual fun openUrlInBrowser(url: String) {
    val context = get<Application>(Application::class.java)
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(intent)
}
