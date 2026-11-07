package org.example.project.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Optional custom status palette. composeApp provides it (PosTheme colors); adminApp leaves it null and gets MaterialTheme fallbacks. */
data class PosCardStatusPalette(
    val available: Color,
    val occupied: Color,
    val reserved: Color,
    val billing: Color,
    val error: Color,
    val availableContainer: Color,
)

val LocalPosCardStatusPalette = compositionLocalOf<PosCardStatusPalette?> { null }

/** Resolves a PosCardStatus to a color: the host's custom palette if provided, else a MaterialTheme fallback. */
@Composable
fun posCardStatusColor(status: PosCardStatus): Color {
    val p = LocalPosCardStatusPalette.current
    return when (status) {
        PosCardStatus.Available, PosCardStatus.Active -> p?.available ?: MaterialTheme.colorScheme.primary
        PosCardStatus.Occupied, PosCardStatus.Pending, PosCardStatus.Warning -> p?.occupied ?: MaterialTheme.colorScheme.tertiary
        PosCardStatus.Reserved -> p?.reserved ?: MaterialTheme.colorScheme.secondary
        PosCardStatus.Billing -> p?.billing ?: MaterialTheme.colorScheme.secondary
        PosCardStatus.Error -> p?.error ?: MaterialTheme.colorScheme.error
        PosCardStatus.Completed, PosCardStatus.Cancelled -> MaterialTheme.colorScheme.outline
        PosCardStatus.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Resolves the *container* (fill) colour behind a status, as opposed to the status colour itself
 * used for borders and text.
 *
 * Available/Active takes a dedicated palette entry because the till uses a specific green wash that
 * is not a simple alpha of its border colour; every other status derives a 15 % wash so a host that
 * provides no palette still gets a coherent fill.
 */
@Composable
fun posCardStatusContainerColor(status: PosCardStatus): Color {
    val p = LocalPosCardStatusPalette.current
    return when (status) {
        PosCardStatus.Available, PosCardStatus.Active ->
            p?.availableContainer ?: MaterialTheme.colorScheme.primaryContainer
        else -> posCardStatusColor(status).copy(alpha = 0.15f)
    }
}
