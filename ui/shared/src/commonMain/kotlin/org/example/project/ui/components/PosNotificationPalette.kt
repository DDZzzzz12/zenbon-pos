package org.example.project.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Optional custom accent palette for notification severity colors.
 * composeApp provides it (from PosTheme semantic colors); adminApp leaves it null
 * and gets MaterialTheme fallbacks. Mirrors [PosCardStatusPalette].
 */
data class PosNotificationPalette(
    val success: Color,
    val warning: Color,
    val error: Color,
)

val LocalPosNotificationPalette = compositionLocalOf<PosNotificationPalette?> { null }

/** Accent color for a toast type: host palette if provided, else a MaterialTheme fallback. */
@Composable
fun PosToastType.accentColor(): Color {
    val p = LocalPosNotificationPalette.current
    return p?.let {
        when (this) {
            PosToastType.SUCCESS -> it.success
            PosToastType.ERROR -> it.error
            PosToastType.WARNING -> it.warning
        }
    } ?: when (this) {
        PosToastType.SUCCESS -> MaterialTheme.colorScheme.primary
        PosToastType.ERROR -> MaterialTheme.colorScheme.error
        PosToastType.WARNING -> MaterialTheme.colorScheme.tertiary
    }
}

/** Accent color for a popup severity: host palette if provided, else a MaterialTheme fallback. */
@Composable
fun PosNotificationSeverity.accentColor(): Color {
    val p = LocalPosNotificationPalette.current
    return p?.let {
        when (this) {
            PosNotificationSeverity.SUCCESS -> it.success
            PosNotificationSeverity.WARNING -> it.warning
            PosNotificationSeverity.ERROR -> it.error
        }
    } ?: when (this) {
        PosNotificationSeverity.SUCCESS -> MaterialTheme.colorScheme.primary
        PosNotificationSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        PosNotificationSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
}
