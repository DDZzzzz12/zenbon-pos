package org.example.project.ui.layout

/**
 * How [PosPaneHost] lets a phone pick its one visible section (the wider layouts show every
 * section and draw no selector at all).
 *  - [Chips]: a wrapping row of chips, one per section (the default, unchanged for every host);
 *  - [Dropdown]: one field naming the current section that opens a list of all of them, for hosts
 *    whose chips wrap to a second line on a phone and push the first section's content down.
 * Swiping between sections works the same with either style.
 */
enum class PaneSelectorStyle {
    Chips,
    Dropdown,
    ;

    companion object {
        /** The style a host gets when it passes none; pinned so existing hosts keep their chips. */
        val Default: PaneSelectorStyle = Chips
    }
}
