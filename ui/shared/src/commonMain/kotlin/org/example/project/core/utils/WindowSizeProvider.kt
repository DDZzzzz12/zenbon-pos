package org.example.project.core.utils

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.HEIGHT_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.HEIGHT_DP_MEDIUM_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosLayout

/**
 * CompositionLocal for WindowSizeClass — provided once at the app root in AppNavigation.
 */
val LocalWindowSizeClass =
    staticCompositionLocalOf<WindowSizeClass> {
        error("No WindowSizeClass provided — wrap your content with ProvideWindowSizeClass")
    }

/**
 * Computes WindowSizeClass via currentWindowAdaptiveInfo() and provides it to the subtree, together
 * with the matching [PosLayout] (screen padding, pane/grid gaps, max content width) as
 * [LocalPosLayout].
 *
 * The layout reads the raw window width rather than the size class: Material's default size classes
 * stop at Expanded (840dp), and Zenbon's layout has a further Large step at 1200dp.
 */
@Composable
fun ProvideWindowSizeClass(content: @Composable () -> Unit) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = with(LocalDensity.current) { widthPx.toDp().value.toInt() }
    CompositionLocalProvider(
        LocalWindowSizeClass provides windowSizeClass,
        LocalPosLayout provides PosLayout.forWidth(widthDp),
    ) {
        content()
    }
}

// --- Width helpers ---

/** Phone-sized: width below 600dp */
fun WindowSizeClass.isCompact(): Boolean = !isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

/** Tablet or larger: width at least 600dp */
fun WindowSizeClass.isAtLeastMedium(): Boolean = isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * Desktop-sized: width at least 840dp AND height at least 480dp.
 * The height guard prevents landscape tablets from triggering Expanded layout.
 */
fun WindowSizeClass.isAtLeastExpanded(): Boolean =
    isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)

// --- Height helpers ---

/** Short screen: height below 480dp */
fun WindowSizeClass.isHeightCompact(): Boolean = !isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)

/** Height at least 480dp */
fun WindowSizeClass.isAtLeastMediumHeight(): Boolean = isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)

/** Height at least 900dp */
fun WindowSizeClass.isAtLeastExpandedHeight(): Boolean = isHeightAtLeastBreakpoint(HEIGHT_DP_EXPANDED_LOWER_BOUND)
