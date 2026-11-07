package org.example.project.ui.guard

/** One surface that can hold unsaved edits. Created by [rememberDirtySource]; tests build it directly. */
class DirtySource(
    private val dirty: () -> Boolean,
    private val onDiscard: () -> Unit,
) {
    val isDirty: Boolean get() = dirty()

    /** Drops the edits (clears the draft / closes the dialog). Called only after the user chose Discard. */
    fun discard() = onDiscard()
}
