package org.example.project.core.utils

// `isDesktop()` now lives in shared-ui (`DesktopPlatform.kt`), same package, because PosTextField
// needs it and shared-ui cannot see this module. Call sites here resolve it unchanged.

/**
 * Returns true if QR code scanning via camera is available on this platform.
 * Currently only available on Android devices with camera hardware.
 */
expect fun isQrScannerAvailable(): Boolean

/**
 * Returns true if document scanning via camera is available on this platform.
 * Currently only available on Android devices with Google Play Services.
 */
expect fun isDocumentScannerAvailable(): Boolean

/**
 * Returns true if a physical (hardware) keyboard is currently attached.
 * On desktop this is always true. On Android, detects USB/Bluetooth keyboards
 * via the system Configuration.
 */
@androidx.compose.runtime.Composable
expect fun hasPhysicalKeyboard(): Boolean

/**
 * Opens a URL in the platform's default browser.
 *
 * @param url The URL to open (e.g. "http://localhost:8080/manual/index.html")
 */
expect fun openUrlInBrowser(url: String)

/**
 * Requests instant tap mode for the touch interceptor (ref-counted).
 * When active, touch taps register near-instantly (no pending resolve delay).
 * Call [releaseInstantTapMode] when the component is no longer visible.
 * No-op on Android.
 */
expect fun requestInstantTapMode()

/**
 * Releases a previous [requestInstantTapMode] request (ref-counted).
 * Instant tap mode stays active as long as any component holds a request.
 * No-op on Android.
 */
expect fun releaseInstantTapMode()

/**
 * Shuts down the operating system.
 * Only meaningful on desktop (Linux/Windows kiosk devices).
 * No-op on Android.
 */
expect fun shutdownSystem()

/**
 * Quits the client application, leaving the host OS running.
 *
 * Distinct from [shutdownSystem], which powers the machine off. On the JVM targets this ends the
 * process; in the browser preview there is no process to end, so it is a no-op.
 */
expect fun exitApplication()
