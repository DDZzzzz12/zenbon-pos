package org.example.project.admin.features.terminal.presentation

/**
 * The three Terminals layouts, chosen from the width the screen's content really gets (the window
 * minus the sidebar and the page padding), not from the window class.
 *
 * - [LARGE]: table beside a 360dp detail panel. The table needs ~740dp for its six fixed columns plus a
 *   readable Terminal column, so the switch sits at [LARGE_MIN_WIDTH_DP].
 * - [MEDIUM]: table alone; the detail opens as a 400dp drawer.
 * - [COMPACT]: card list, then a detail page and an edit page.
 */
enum class TerminalsLayoutMode {
    LARGE,
    MEDIUM,
    COMPACT,
    ;

    companion object {
        const val LARGE_MIN_WIDTH_DP: Float = 1120f
        const val MEDIUM_MIN_WIDTH_DP: Float = 600f

        fun forContentWidth(contentWidthDp: Float): TerminalsLayoutMode =
            when {
                contentWidthDp >= LARGE_MIN_WIDTH_DP -> LARGE
                contentWidthDp >= MEDIUM_MIN_WIDTH_DP -> MEDIUM
                else -> COMPACT
            }
    }
}
