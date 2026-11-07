package org.example.project.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * This type scale with [family] on every style.
 *
 * Compose resolves a `FontFamily(a, b)` by weight and style, not by glyph coverage, so a CJK font
 * cannot be chained behind the Latin one as a fallback — the whole scale has to switch. Noto Sans SC
 * carries Latin glyphs too, so mixed text still renders. Written out because Compose offers no
 * "apply this family everywhere" helper, and a missed style would silently draw boxes for that role.
 */
fun Typography.withFontFamily(family: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
