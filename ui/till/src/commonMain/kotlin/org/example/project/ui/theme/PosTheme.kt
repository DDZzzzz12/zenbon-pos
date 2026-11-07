package org.example.project.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.example.project.core.network.HttpCall
import org.example.project.features.settings.domain.enums.ThemeMode
import org.koin.compose.koinInject

@Composable
fun PosTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }

    val colorScheme = if (isDark) PosDarkColorScheme else PosLightColorScheme
    val posColors = if (isDark) PosDarkSemanticColors else PosLightSemanticColors

    // Browser build only (null on Android/desktop, where the system falls back per glyph). Applied
    // whatever the language: Chinese data (a product or customer name in hanzi) shows up under a
    // German or English UI too, and Compose does not fall back by glyph coverage, so without the
    // font those characters draw as boxes. Same choice as adminApp; the font carries Latin glyphs.
    val cjkFamily = rememberCjkFontFamily(koinInject<HttpCall>())
    val baseTypography = posTypography(interFontFamily())
    val typography = if (cjkFamily != null) baseTypography.withFontFamily(cjkFamily) else baseTypography

    CompositionLocalProvider(LocalCjkFontFamily provides cjkFamily) {
        ProvidePosPalettes(posColors) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = typography,
                shapes = PosShapes,
                content = content,
            )
        }
    }
}

object PosTheme {
    val colors: PosColors
        @Composable
        get() = LocalPosColors.current
}
