package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.example.project.ui.components.LocalPosCardStatusPalette
import org.example.project.ui.components.LocalPosNotificationPalette
import org.example.project.ui.components.PosCardStatusPalette
import org.example.project.ui.components.PosNotificationPalette

/**
 * Provides the Zenbon status colours — [LocalPosColors] plus the card-status and notification
 * palettes derived from them — so every app maps a status to the same colour. Without this a host
 * falls back to MaterialTheme roles, which is how adminApp used to show statuses in other hues.
 */
@Composable
fun ProvidePosPalettes(
    posColors: PosColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPosColors provides posColors,
        LocalPosCardStatusPalette provides
            PosCardStatusPalette(
                available = posColors.posAvailable,
                occupied = posColors.posOccupied,
                reserved = posColors.posReserved,
                billing = posColors.posBilling,
                error = posColors.posError,
                availableContainer = posColors.posAvailableBg,
            ),
        LocalPosNotificationPalette provides
            PosNotificationPalette(
                success = posColors.posAvailable,
                warning = posColors.posOccupied,
                error = posColors.posError,
            ),
        content = content,
    )
}
