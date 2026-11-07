package org.example.project.ui.components.filter

/**
 * Holds the [PosFilterPanelState] of each screen above the layout that can be torn down and rebuilt
 * (adminApp swaps its compact and wide shell at 600dp, each with its own NavHost), so an open panel
 * and its unapplied draft survive that rebuild. Pure: no Compose, unit-testable.
 *
 * States are keyed by one stable string per screen. The draft type differs per screen, so the map is
 * untyped and [getOrCreate] casts: a key must therefore always be used with the same filter type
 * (one key per screen, never shared). The host decides when a state is stale and calls [clear],
 * [clear] with a key or [clearExcept]; a resize is not such a moment, a navigation to another screen is.
 */
class PosFilterPanelStore {
    private val states = mutableMapOf<String, PosFilterPanelState<*>>()

    val size: Int get() = states.size

    /** The state stored under [key], created from [applied] when there is none yet. */
    @Suppress("UNCHECKED_CAST") // one key = one screen = one filter type; see the class comment
    fun <T> getOrCreate(
        key: String,
        applied: T,
    ): PosFilterPanelState<T> = states.getOrPut(key) { PosFilterPanelState(applied) } as PosFilterPanelState<T>

    fun contains(key: String): Boolean = key in states

    /** Drops every state (sign-out). */
    fun clear() = states.clear()

    /** Drops the state of one screen. */
    fun clear(key: String) {
        states.remove(key)
    }

    /**
     * A real navigation: drops every state except the one of the screen now showing ([keep], which
     * may be null for a screen without a panel), so returning to a screen later never shows a stale
     * open panel, while the screen just entered keeps the state it created.
     */
    fun clearExcept(keep: String?) {
        states.keys.retainAll { it == keep }
    }
}
