package org.example.project.core.presentation.components.notification

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal for [GlobalOverlayViewModel].
 *
 * Provided at the root of each client (`AppNavigation` for composeApp, `AdminApp` for adminApp)
 * so any screen can access the global overlay queue.
 */
val LocalGlobalOverlayViewModel =
    compositionLocalOf<GlobalOverlayViewModel> {
        error("GlobalOverlayViewModel not provided. Provide it at the root level using CompositionLocalProvider.")
    }
