package org.example.project.core.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.net.URI
import kotlin.system.exitProcess

// Desktop platform detection: the `isDesktop()` actual now sits beside its expect in shared-ui.

/**
 * QR scanner is not available on desktop (no camera access).
 */
actual fun isQrScannerAvailable(): Boolean = false

/**
 * Document scanner is not available on desktop (no camera access).
 */
actual fun isDocumentScannerAvailable(): Boolean = false

/**
 * Detects whether a physical keyboard is attached on Desktop.
 * - Linux: checks `/dev/input/by-id/` for keyboard device symlinks (`*-kbd`)
 * - Windows: queries WMI for keyboard devices via PowerShell
 * - macOS/other: defaults to true
 *
 * Re-evaluates when the window regains focus to handle USB/Bluetooth hot-plug.
 */
@androidx.compose.runtime.Composable
actual fun hasPhysicalKeyboard(): Boolean {
    var hasKeyboard by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(detectPhysicalKeyboard())
    }

    // Re-check when window gains focus (covers USB/Bluetooth hot-plug)
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    androidx.compose.runtime.LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            hasKeyboard = detectPhysicalKeyboard()
        }
    }

    return hasKeyboard
}

/**
 * Platform-specific keyboard detection dispatcher.
 */
private fun detectPhysicalKeyboard(): Boolean {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("linux") -> detectLinuxKeyboard()
        os.contains("windows") -> detectWindowsKeyboard()
        else -> true // macOS and others: assume keyboard exists
    }
}

/**
 * Linux: checks `/dev/input/by-id/` for keyboard device symlinks.
 * Files like `usb-Logitech_USB_Keyboard-event-kbd` indicate attached keyboards.
 */
private fun detectLinuxKeyboard(): Boolean {
    return try {
        val byIdDir = java.io.File("/dev/input/by-id")
        if (byIdDir.exists() && byIdDir.isDirectory) {
            val kbdDevices =
                byIdDir.listFiles()?.filter {
                    it.name.contains("kbd", ignoreCase = true)
                } ?: return true
            // Exclude mice/gamepads that expose a secondary kbd HID interface
            kbdDevices.any { file ->
                val name = file.name.lowercase()
                !name.contains("mouse") && !name.contains("gamepad")
            }
        } else {
            true
        }
    } catch (_: Exception) {
        true
    }
}

/**
 * Windows: queries WMI for keyboard devices via PowerShell.
 * Returns true if any Win32_Keyboard device is found.
 */
private fun detectWindowsKeyboard(): Boolean =
    try {
        val process =
            ProcessBuilder(
                "powershell",
                "-Command",
                "Get-CimInstance -ClassName Win32_Keyboard | Measure-Object | Select-Object -ExpandProperty Count",
            ).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        process.waitFor()
        val count = output.toIntOrNull() ?: 0
        count > 0
    } catch (_: Exception) {
        true
    }

actual fun requestInstantTapMode() {
    org.example.project.requestInstantTapMode()
}

actual fun releaseInstantTapMode() {
    org.example.project.releaseInstantTapMode()
}

/**
 * Opens a URL in the system's default browser on Desktop.
 */
actual fun openUrlInBrowser(url: String) {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(url))
    }
}

/**
 * Shuts down the operating system via platform-specific command.
 * - Linux: `systemctl poweroff`
 * - Windows: `shutdown /s /t 0`
 */
actual fun exitApplication() {
    exitProcess(0)
}

actual fun shutdownSystem() {
    val os = System.getProperty("os.name", "").lowercase()
    try {
        when {
            os.contains("linux") -> ProcessBuilder("systemctl", "poweroff").start()
            os.contains("windows") -> ProcessBuilder("shutdown", "/s", "/t", "0").start()
        }
    } catch (_: Exception) {
        // Best-effort shutdown; nothing to do if it fails
    }
}
