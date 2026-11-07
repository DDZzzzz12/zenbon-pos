package org.example.project.core.utils

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass

/**
 * Adaptive component sizing following accessibility and platform guidelines.
 */
object AdaptiveSize {
    /**
     * Get adaptive button height ensuring minimum touch target.
     */
    fun getButtonHeight(windowSize: WindowSizeClass): Dp =
        when {
            windowSize.isCompact() -> 42.dp // Minimum touch target
            windowSize.isAtLeastExpanded() -> 54.dp // Prominent on desktop
            else -> 48.dp
        }

    /**
     * Get adaptive text field height.
     */
    fun getTextFieldHeight(windowSize: WindowSizeClass): Dp =
        when {
            windowSize.isCompact() -> 70.dp
            windowSize.isAtLeastExpanded() -> 74.dp
            else -> 72.dp
        }

    /**
     * Get adaptive card dimensions based on window size.
     */
    fun getCardSize(windowSize: WindowSizeClass): Pair<Dp, Dp> {
        val width =
            when {
                windowSize.isCompact() -> 340.dp
                windowSize.isAtLeastExpanded() -> 480.dp
                else -> 420.dp
            }

        val height =
            when {
                windowSize.isHeightCompact() -> 520.dp
                windowSize.isAtLeastExpandedHeight() -> 640.dp
                else -> 580.dp
            }

        return width to height
    }

    /**
     * Get adaptive elevation for cards and surfaces.
     */
    fun getCardElevation(windowSize: WindowSizeClass): Dp =
        when {
            windowSize.isCompact() -> 4.dp // Subtle on mobile
            windowSize.isAtLeastExpanded() -> 8.dp // Strong presence on desktop
            else -> 6.dp
        }

    /**
     * Get adaptive icon size for consistent visual hierarchy.
     */
    fun getIconSize(windowSize: WindowSizeClass): Dp =
        when {
            windowSize.isCompact() -> 24.dp // Standard mobile size
            windowSize.isAtLeastExpanded() -> 32.dp // More prominent on desktop
            else -> 28.dp
        }
}

/**
 * Adaptive typography scaling based on screen size.
 */
object AdaptiveTypography {
    /**
     * Scale font size based on window size class.
     */
    fun scaleFontSize(
        baseSize: Float,
        windowSize: WindowSizeClass,
    ): Float =
        when {
            windowSize.isCompact() -> baseSize
            windowSize.isAtLeastExpanded() -> baseSize * 1.2f
            else -> baseSize * 1.1f
        }

    /**
     * Get adaptive text style with proper scaling.
     */
    fun getAdaptiveTextStyle(
        baseStyle: TextStyle,
        windowSize: WindowSizeClass,
    ): TextStyle {
        val scaledFontSize = scaleFontSize(baseStyle.fontSize.value, windowSize)
        return baseStyle.copy(fontSize = scaledFontSize.sp)
    }

    /**
     * Predefined adaptive text styles for common use cases.
     */
    object Styles {
        fun title(windowSize: WindowSizeClass) =
            TextStyle(
                fontSize = scaleFontSize(20f, windowSize).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )

        fun subtitle(windowSize: WindowSizeClass) =
            TextStyle(
                fontSize = scaleFontSize(14f, windowSize).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )

        fun button(windowSize: WindowSizeClass) =
            TextStyle(
                fontSize = scaleFontSize(14f, windowSize).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )

        fun body(windowSize: WindowSizeClass) =
            TextStyle(
                fontSize = scaleFontSize(14f, windowSize).sp,
                fontWeight = FontWeight.Normal,
            )

        fun caption(windowSize: WindowSizeClass) =
            TextStyle(
                fontSize = scaleFontSize(12f, windowSize).sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.1.sp,
            )
    }
}

/**
 * Adaptive layout utilities for responsive design.
 */
object AdaptiveLayout {
    /**
     * Get number of columns for grid layouts based on window size.
     */
    fun getGridColumns(windowSize: WindowSizeClass): Int =
        when {
            windowSize.isCompact() -> 1 // Single column
            windowSize.isAtLeastExpanded() -> 3 // Three columns
            else -> 2
        }

    /**
     * Check if layout should use navigation rail instead of bottom navigation.
     */
    fun shouldUseNavigationRail(windowSize: WindowSizeClass): Boolean = !windowSize.isCompact()

    /**
     * Check if layout should use permanent navigation drawer.
     */
    fun shouldUsePermanentDrawer(windowSize: WindowSizeClass): Boolean = windowSize.isAtLeastExpanded()

    /**
     * Get maximum content width to maintain readability on large screens.
     */
    fun getMaxContentWidth(windowSize: WindowSizeClass): Dp =
        when {
            windowSize.isCompact() -> Dp.Unspecified
            windowSize.isAtLeastExpanded() -> 840.dp
            else -> 600.dp
        }
}

/** Smaller padding on compact, default on medium+. */
fun WindowSizeClass.cardPadding(): Dp = if (isCompact()) 12.dp else 16.dp
