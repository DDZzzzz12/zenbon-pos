package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import org.example.project.core.network.HttpCall

/**
 * The CJK font family for this platform, fetching it first if the platform needs that.
 *
 * Browser build: Compose/wasm renders through Skia onto a canvas and has no CJK glyphs of its own,
 * so every Chinese character draws as a missing-glyph box until the font bytes are handed to it. The
 * font is fetched once from pos-server (`/font/…`, same origin, works offline) and the result is
 * null until it arrives.
 *
 * Android and desktop: always null — the system supplies CJK glyphs through font fallback.
 */
@Composable
expect fun rememberCjkFontFamily(httpCall: HttpCall): FontFamily?
