package org.example.project.admin.features.tables.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the Tische list is laid out, decided from the width the screen really has (the content beside
 * the sidebar), not from the window class:
 * [WIDE] a location sidebar and the inline-editable grid, [MEDIUM] chips and a read-only table whose
 * rows edit in a dialog, [COMPACT] list rows.
 */
enum class TablesLayout {
    WIDE,
    MEDIUM,
    COMPACT,
    ;

    companion object {
        /** Sidebar (200dp) + a five-column grid that still edits inline. */
        val WideMinWidth: Dp = 720.dp

        /** The read-only table with its icon actions. */
        val MediumMinWidth: Dp = 560.dp

        fun forWidth(contentWidth: Dp): TablesLayout =
            when {
                contentWidth >= WideMinWidth -> WIDE
                contentWidth >= MediumMinWidth -> MEDIUM
                else -> COMPACT
            }
    }
}
