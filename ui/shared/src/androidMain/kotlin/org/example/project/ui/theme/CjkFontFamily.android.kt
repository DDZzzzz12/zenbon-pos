package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import org.example.project.core.network.HttpCall

// Android ships CJK system fonts and falls back to them per glyph; nothing to load.
@Composable
actual fun rememberCjkFontFamily(httpCall: HttpCall): FontFamily? = null
