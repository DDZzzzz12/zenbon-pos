package org.example.project.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Zenbon colour roles, shared by composeApp (till), adminApp (back office) and any later surface.
 * Source of truth: the "Zenbon" design system (tokens.json). Teal from the Zenbon mark is the only
 * interactive hue; neutrals carry a faint tint of the mark's ink (#1A1A2E).
 *
 * Every role is set explicitly — Material's baseline defaults are purple-tinted, so an unset
 * container or tertiary role would leak a hue that is not in the system. `secondary*` mirrors
 * `primary*` on purpose: FilledTonalButton and selected FilterChips read secondaryContainer, and
 * Zenbon has no second accent. `tertiary` is the amber text tone, the fallback warning colour for a
 * host that provides no PosNotificationPalette.
 */

val PosLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF00796B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0F2F1),
        onPrimaryContainer = Color(0xFF004D40),
        inversePrimary = Color(0xFF00BFA5),
        secondary = Color(0xFF00796B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE0F2F1),
        onSecondaryContainer = Color(0xFF004D40),
        tertiary = Color(0xFFB45309),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFEF3C7),
        onTertiaryContainer = Color(0xFF78350F),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF1A1A2E),
        surface = Color(0xFFF4F4F7),
        onSurface = Color(0xFF1A1A2E),
        surfaceVariant = Color(0xFFEAEAF0),
        onSurfaceVariant = Color(0xFF5C5C70),
        surfaceTint = Color(0xFF00796B),
        inverseSurface = Color(0xFF1A1A2E),
        inverseOnSurface = Color(0xFFF5F5F7),
        error = Color(0xFFDC2626),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
        outline = Color(0xFF767688),
        outlineVariant = Color(0xFFE4E4EA),
        scrim = Color(0xFF1A1A2E),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFEAEAF0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFF4F4F7),
        surfaceContainerHighest = Color(0xFFEAEAF0),
    )

val PosDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF00BFA5),
        onPrimary = Color(0xFF1A1A2E),
        primaryContainer = Color(0xFF003D36),
        onPrimaryContainer = Color(0xFFA7FFEB),
        inversePrimary = Color(0xFF00796B),
        secondary = Color(0xFF00BFA5),
        onSecondary = Color(0xFF1A1A2E),
        secondaryContainer = Color(0xFF003D36),
        onSecondaryContainer = Color(0xFFA7FFEB),
        tertiary = Color(0xFFFCD34D),
        onTertiary = Color(0xFF1A1A2E),
        tertiaryContainer = Color(0xFF4A3410),
        onTertiaryContainer = Color(0xFFFEF3C7),
        background = Color(0xFF12121F),
        onBackground = Color(0xFFF5F5F7),
        surface = Color(0xFF1A1A2E),
        onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF2C2C46),
        onSurfaceVariant = Color(0xFFA8A8BC),
        surfaceTint = Color(0xFF00BFA5),
        inverseSurface = Color(0xFFF5F5F7),
        inverseOnSurface = Color(0xFF1A1A2E),
        error = Color(0xFFF87171),
        onError = Color(0xFF1A1A2E),
        errorContainer = Color(0xFF4C1D1D),
        onErrorContainer = Color(0xFFFECACA),
        outline = Color(0xFF7A7A92),
        outlineVariant = Color(0xFF33334D),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2C2C46),
        surfaceDim = Color(0xFF12121F),
        surfaceContainerLowest = Color(0xFF12121F),
        surfaceContainerLow = Color(0xFF16162A),
        surfaceContainer = Color(0xFF22223A),
        surfaceContainerHigh = Color(0xFF262640),
        surfaceContainerHighest = Color(0xFF2C2C46),
    )
