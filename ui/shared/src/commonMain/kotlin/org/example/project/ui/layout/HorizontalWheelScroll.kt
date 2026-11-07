package org.example.project.ui.layout

/**
 * Pixels to scroll a horizontal strip for one wheel event, or null to leave the event alone.
 *
 * Only a **pure vertical** wheel (`deltaX == 0`) is taken: on wasm a mouse wheel over a
 * `horizontalScroll` scrolls the page instead of the strip (verified 2026-10-11), while trackpad and
 * shift-wheel horizontal deltas already work natively. At the end of the strip we return null so the
 * wheel falls through to the page and the user is never trapped inside it.
 *
 * No multiplier: wasm already delivers pixel deltas (100 per wheel tick); a x40 factor flung a row to
 * its end in three ticks.
 *
 * The caller multiplies by the screen density: scrolling takes device pixels, wheel deltas are CSS pixels.
 */
internal fun wheelScrollAmount(
    deltaX: Float,
    deltaY: Float,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Float? {
    if (deltaX != 0f || deltaY == 0f) return null
    val canMove = if (deltaY > 0f) canScrollForward else canScrollBackward
    return if (canMove) deltaY else null
}

/** One wheel tick on wasm is 100 CSS pixels. */
internal const val WHEEL_TICK_CSS_PX = 100f

/** An arrow button moves the strip this many wheel ticks. */
internal const val ARROW_STEP_TICKS = 2

/** Device pixels for one wheel tick at [density] (device pixels per CSS pixel). */
internal fun wheelTickPx(density: Float): Float = WHEEL_TICK_CSS_PX * density

/** Device pixels for one arrow click, so an arrow and the wheel agree at every pixel ratio. */
internal fun arrowStepPx(density: Float): Float = wheelTickPx(density) * ARROW_STEP_TICKS
