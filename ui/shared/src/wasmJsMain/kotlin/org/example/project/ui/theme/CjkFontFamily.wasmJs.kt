package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import org.example.project.core.network.HttpCall

@Composable
actual fun rememberCjkFontFamily(httpCall: HttpCall): FontFamily? {
    // CjkFontLoader is idempotent, so this fetches at most once per session.
    LaunchedEffect(Unit) { CjkFontLoader.load(httpCall) }
    return CjkFontLoader.family
}
