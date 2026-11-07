package org.example.project.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The current window's [PosLayout]. Provided by `ProvideWindowSizeClass` at each app root; the
 * default ([PosLayout.Medium]) only applies to previews and tests that render without it.
 */
val LocalPosLayout = staticCompositionLocalOf { PosLayout.Medium }
