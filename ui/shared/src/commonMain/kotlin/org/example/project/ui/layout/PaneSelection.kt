package org.example.project.ui.layout

/**
 * Pure mapping between [PosPaneHost]'s phone selector and its pager, shared by both
 * [PaneSelectorStyle]s so chips and dropdown cannot disagree about which page an id means.
 */
object PaneSelection {
    /** The pager page of the section [id], or null when no section carries it (no scroll then). */
    fun pageOf(
        sections: List<PaneSection>,
        id: String,
    ): Int? = sections.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /** The title the dropdown shows for [page]; out-of-range pages clamp to the nearest section. */
    fun titleAt(
        sections: List<PaneSection>,
        page: Int,
    ): String {
        require(sections.isNotEmpty()) { "a pane host needs at least one section" }
        return sections[page.coerceIn(0, sections.lastIndex)].title
    }

    /** Whether a phone needs a selector at all: a single section is shown without one. */
    fun needsSelector(sectionCount: Int): Boolean = sectionCount > 1
}
