package org.example.project

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.example.project.core.auth.SettingManager
import org.koin.java.KoinJavaComponent.get

/**
 * Composable wrapper that applies custom density scaling.
 *
 * This function uses the UI density from user settings (SettingManager),
 * with a fallback to JVM system properties for backward compatibility.
 *
 * Usage:
 * ```kotlin
 * Window(...) {
 *     WithScaling {
 *         App()
 *     }
 * }
 * ```
 *
 * The density can be controlled:
 * 1. Via Settings UI (General Settings -> UI Scale slider)
 * 2. Via JVM property `sun.java2d.uiScale` (if `sun.java2d.uiScale.enabled=true`)
 *
 * @param content The composable content to render with scaled density
 */
@Composable
fun WithScaling(content: @Composable () -> Unit) {
    // Try to get density from settings, with JVM property fallback
    val settingManager: SettingManager = remember { get(SettingManager::class.java) }
    val settingsDensity by settingManager.uiDensity.collectAsState()

    // JVM property override (for backward compatibility)
    val jvmOverride = System.getProperty("sun.java2d.uiScale.enabled", "false") == "true"
    val jvmDensity = System.getProperty("sun.java2d.uiScale", "1.0").toFloatOrNull() ?: 1.0f

    // Use JVM property if explicitly enabled, otherwise use settings
    val effectiveDensity = if (jvmOverride) jvmDensity else settingsDensity

    // Only apply custom density if it differs from default
    if (effectiveDensity != 1.0f) {
        CompositionLocalProvider(LocalDensity provides Density(effectiveDensity)) {
            content()
        }
    } else {
        content()
    }
}
