package org.example.project.ui.guard

/** Decides what to do with a browser `popstate` while forms may hold unsaved edits. */
class HistoryTraversalGate {
    enum class Decision { Pass, Hold }

    /**
     * [hasState]: a history traversal carries a state; a typed #hash does not and is not guarded.
     * The traversals the guard replays itself on Discard never get here: the browser glue consumes
     * them before asking.
     */
    fun onPopState(
        hasState: Boolean,
        dirty: Boolean,
    ): Decision = if (hasState && dirty) Decision.Hold else Decision.Pass
}
