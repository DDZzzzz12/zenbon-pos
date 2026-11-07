package org.example.project.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier

/**
 * Lets a vertical mouse wheel drive a horizontal [state]. wasm only: there the browser delivers wheel
 * deltas in CSS pixels and a plain `horizontalScroll` ignores a vertical one (verified 2026-10-11).
 * Desktop and Android deliver lines/ticks, so their actual is a no-op: a handler there would move
 * the strip about 1px and swallow the page's wheel. There the strip is NOT scrolled by a plain
 * vertical wheel; it scrolls by shift+wheel, trackpad, touch or the arrow buttons.
 */
internal expect fun Modifier.horizontalWheelDrive(state: ScrollState): Modifier
