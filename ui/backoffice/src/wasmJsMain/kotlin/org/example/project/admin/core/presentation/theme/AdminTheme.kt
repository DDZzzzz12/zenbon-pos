package org.example.project.admin.core.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.HttpCall
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.ui.components.LocalPosDialogPhoneDefault
import org.example.project.ui.components.LocalPosTextFieldAppearance
import org.example.project.ui.components.PosDialogPhoneMode
import org.example.project.ui.components.PosTextFieldAppearance
import org.example.project.ui.theme.PosLightColorScheme
import org.example.project.ui.theme.PosLightSemanticColors
import org.example.project.ui.theme.PosShapes
import org.example.project.ui.theme.ProvidePosPalettes
import org.example.project.ui.theme.posTypography
import org.example.project.ui.theme.rememberCjkFontFamily
import org.example.project.ui.theme.withFontFamily
import org.koin.compose.koinInject

/**
 * Material 3 theme wrapper for the admin panel, on the shared Zenbon theme from shared-ui: the same
 * colour roles, status palettes, square [PosShapes] and type scale as the till. Light scheme only for
 * now; [org.example.project.ui.theme.PosDarkColorScheme] is ready when a theme switch is added.
 *
 * Applies the CJK font to the whole type scale once it has loaded. Noto Sans SC carries Latin glyphs
 * too, so one family covers mixed text — deliberately not a two-font fallback chain, because
 * Compose's `FontFamily(a, b)` resolves by weight and style, not by glyph coverage, and would not do
 * what it looks like it does.
 *
 * The font is loaded regardless of the selected UI language: Chinese *data* — a payment type's 中文
 * name, a product name, a customer with hanzi — shows up under a German or English UI too, and
 * without the font those characters draw as tofu boxes. Gating the load on `language == CHINESE`
 * (the old behaviour) left exactly that data unrendered on every non-Chinese install. The ~1.6 MB
 * GB2312 subset is fetched once, lazily, from pos-server (same origin), so an offline install still
 * gets it.
 */
@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    val httpCall: HttpCall = koinInject()

    // Fetched at most once per session; null until it arrives.
    val cjkFamily = rememberCjkFontFamily(httpCall)
    val language = LocalAppLanguage.current
    val baseTypography = posTypography()
    val typography =
        if (cjkFamily != null) {
            baseTypography.withFontFamily(cjkFamily).let { if (language == Language.CHINESE) it else it.withHalfWidthPunctuation() }
        } else {
            baseTypography
        }

    ProvidePosPalettes(PosLightSemanticColors) {
        MaterialTheme(colorScheme = PosLightColorScheme, typography = typography, shapes = PosShapes) {
            // adminApp is a back-office console on a mouse-driven screen, so its form fields default to
            // the flat treatment rather than Material's outlined box with a notched floating label.
            // Set here rather than at ~140 call sites, and deliberately NOT in shared-ui's own default:
            // composeApp is the till, where prices and quantities are typed on an on-screen keyboard and
            // the outlined box is the bigger, more explicit target. The two apps want different answers.
            // Likewise form-like dialogs fill the screen on a phone here; the till keeps the compact card.
            CompositionLocalProvider(
                LocalPosTextFieldAppearance provides PosTextFieldAppearance.Flat,
                LocalPosDialogPhoneDefault provides PosDialogPhoneMode.Auto,
            ) {
                content()
            }
        }
    }
}

/**
 * NEW-21: Noto Sans SC draws the curly quotes U+201C/U+201D (and the other CJK punctuation) on a
 * full em, the CJK convention, so German „…“ showed a wide gap before the closing quote ("„Apfelschorle
 * 0,5 l PET “ löschen?"). The glyph is not missing from the subset; it is full-width. The font's own
 * `halt` feature (half-width alternates) sets that punctuation on half an em, as Latin text expects.
 * A Chinese UI keeps the full-width forms.
 */
private fun Typography.withHalfWidthPunctuation(): Typography {
    fun TextStyle.halt() = copy(fontFeatureSettings = "halt")
    return copy(
        displayLarge = displayLarge.halt(),
        displayMedium = displayMedium.halt(),
        displaySmall = displaySmall.halt(),
        headlineLarge = headlineLarge.halt(),
        headlineMedium = headlineMedium.halt(),
        headlineSmall = headlineSmall.halt(),
        titleLarge = titleLarge.halt(),
        titleMedium = titleMedium.halt(),
        titleSmall = titleSmall.halt(),
        bodyLarge = bodyLarge.halt(),
        bodyMedium = bodyMedium.halt(),
        bodySmall = bodySmall.halt(),
        labelLarge = labelLarge.halt(),
        labelMedium = labelMedium.halt(),
        labelSmall = labelSmall.halt(),
    )
}
