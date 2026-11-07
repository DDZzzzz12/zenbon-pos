package org.example.project.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Zenbon type scale with [fontFamily] on every style it defines.
 *
 * The family is a parameter because the Inter files are composeApp's generated Compose resources,
 * which cannot cross into adminApp's wasm build; adminApp passes the platform default and then swaps
 * the whole scale to the CJK family with [withFontFamily]. Styles the scale does not define
 * (displayLarge…headlineMedium, titleSmall) keep Material's defaults.
 */
fun posTypography(fontFamily: FontFamily = FontFamily.Default): Typography =
    Typography(
        displaySmall = style(fontFamily, FontWeight.Bold, 24, 32),
        headlineSmall = style(fontFamily, FontWeight.Bold, 20, 28),
        titleLarge = style(fontFamily, FontWeight.Bold, 18, 26),
        titleMedium = style(fontFamily, FontWeight.SemiBold, 16, 24),
        bodyLarge = style(fontFamily, FontWeight.Normal, 15, 22),
        bodyMedium = style(fontFamily, FontWeight.Normal, 14, 20),
        bodySmall = style(fontFamily, FontWeight.Normal, 13, 18),
        labelLarge = style(fontFamily, FontWeight.SemiBold, 14, 20),
        labelMedium = style(fontFamily, FontWeight.Medium, 13, 18),
        labelSmall = style(fontFamily, FontWeight.Normal, 12, 16),
    )

private fun style(
    fontFamily: FontFamily,
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
): TextStyle =
    TextStyle(
        fontFamily = fontFamily,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
    )
