package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import org.example.project.core.network.HttpCall

// The JVM desktop resolves CJK glyphs through the operating system's fonts; nothing to load.
@Composable
actual fun rememberCjkFontFamily(httpCall: HttpCall): FontFamily? = null
