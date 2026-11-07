package org.example.project.ui.screens.settings

import org.example.project.ui.layout.PaneSection

/**
 * The section to show: the one whose id is [selectedId], or the first when that id is no longer among
 * [sections] (a capability change can drop a section while it is selected). [sections] must not be empty.
 */
fun selectedSectionOrFirst(
    sections: List<PaneSection>,
    selectedId: String?,
): PaneSection = sections.firstOrNull { it.id == selectedId } ?: sections.first()
