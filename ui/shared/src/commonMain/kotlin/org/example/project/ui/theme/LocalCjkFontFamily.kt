package org.example.project.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * The loaded CJK font family, or null where none is needed or it has not arrived yet.
 *
 * Provided by the host theme. Only the browser build ever fills it: Android and desktop draw
 * Chinese through the system's own fallback fonts. Read it to render a single CJK label (the
 * language menu's "中文") regardless of which language is currently selected.
 */
val LocalCjkFontFamily = compositionLocalOf<FontFamily?> { null }
