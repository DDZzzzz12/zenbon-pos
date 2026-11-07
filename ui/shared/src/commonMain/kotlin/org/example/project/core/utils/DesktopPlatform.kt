/**
 * Platform probe needed by the shared UI components.
 *
 * Only [isDesktop] lives here. The rest of composeApp's platform surface (camera/QR/document
 * scanning, hardware-keyboard detection, opening a browser, OS shutdown) stays in that module's
 * `Platform.kt` — those are app concerns, and dragging them down would force this module to answer
 * for them on every target it builds for, including wasmJs.
 *
 * The package matches composeApp's on purpose so existing call sites resolve unchanged; the FILE
 * name must differ from `Platform.kt`, since two same-package, same-named files across two modules
 * compile fine but produce colliding JVM facade classes that only fail at runtime.
 *
 * @since 1.0.0
 */
package org.example.project.core.utils

/**
 * Returns true if running on desktop platform.
 */
expect fun isDesktop(): Boolean
