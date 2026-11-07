package org.example.project.ui.components.filter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Edit buffer of a filter panel: the panel edits [value] freely and only the Apply action copies it
 * into the applied filter. Opening the panel calls [reopen] so an abandoned edit never leaks into
 * the next visit.
 */
class PosFilterDraft<T>(
    applied: T,
) {
    var value: T by mutableStateOf(applied)

    /** Discards any edit and starts again from the currently [applied] filter. */
    fun reopen(applied: T) {
        value = applied
    }

    /** True when the draft differs from the [applied] filter. */
    fun isChanged(applied: T): Boolean = value != applied
}
