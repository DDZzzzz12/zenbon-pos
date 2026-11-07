package org.example.project.ui.components.pdf

/**
 * What the focused page field does with a key event: Esc cancels the edit on its KeyDown and swallows
 * its KeyUp; every other key passes through (Enter is the field's Go action).
 *
 * The cancel runs on KeyDown because a browser does not reliably hand the field the KeyUp (browser
 * pass 4, B2: the typed number stayed with the caret). Clearing focus on KeyDown is safe for back
 * navigation: Compose's skiko back input (`BackNavigationEventInput`) reacts to Esc KeyDown only, and
 * that KeyDown is consumed here. A held Esc repeats KeyDown, which cancels again; cancel is idempotent.
 */
internal enum class PdfPageJumpEscape(
    val consumes: Boolean,
) {
    PassThrough(consumes = false),
    Cancel(consumes = true),
    Swallow(consumes = true),
    ;

    companion object {
        fun of(
            isEscape: Boolean,
            isKeyDown: Boolean,
        ): PdfPageJumpEscape =
            when {
                !isEscape -> PassThrough
                isKeyDown -> Cancel
                else -> Swallow
            }
    }
}
