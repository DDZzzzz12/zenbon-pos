package org.example.project.ui.layout

/** How a group of peer sections is placed for the width the content actually has. */
sealed interface PaneLayout {
    /** Phone: one section visible at a time, chosen from a selector. */
    data object Single : PaneLayout

    /** Between phone and two columns: every section, one under the other. */
    data object Stacked : PaneLayout

    /** Wide: [count] sections side by side. */
    data class Columns(
        val count: Int,
    ) : PaneLayout
}
