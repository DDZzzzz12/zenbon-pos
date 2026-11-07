package org.example.project.ui.components.filter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything a screen holds for its [PosFilterPanel]: the [draft], whether the panel [isOpen], and
 * the rules for opening, applying and resetting it. Create it with [rememberPosFilterPanelState]
 * **above** any branch that switches the layout (sticky vs scrolling header, search in the bar vs in
 * the panel), so a window resize that changes the branch keeps an open panel and its draft.
 *
 * [applied] is the currently applied filter; [rememberPosFilterPanelState] keeps it current.
 */
class PosFilterPanelState<T>(
    applied: T,
) {
    /** The applied filter [open] starts the draft from. */
    var applied: T = applied

    val draft = PosFilterDraft(applied)

    var isOpen: Boolean by mutableStateOf(false)
        private set

    /** Opens the panel on a fresh draft of the [applied] filter, so an abandoned edit never returns. */
    fun open() {
        draft.reopen(applied)
        isOpen = true
    }

    /** Close, Abbrechen or a tap outside: the draft is dropped (the next [open] starts afresh). */
    fun close() {
        isOpen = false
    }

    /**
     * The panel's apply button: hands the draft to [onApply], then closes. Deliberately not named
     * `apply`: that clashes with the stdlib scope function, so `state.apply { ... }` would read as a
     * configuration block while silently applying the draft.
     */
    fun applyDraft(onApply: (T) -> Unit) {
        onApply(draft.value)
        isOpen = false
    }

    /**
     * The panel's reset action (Zenbon rule): [clearDraft] clears the panel's fields in the draft; on
     * a wide window the reset also applies at once through [applyReset], on a [compact] one it only
     * clears the draft and waits for the apply button. The panel stays open either way.
     */
    fun reset(
        compact: Boolean,
        clearDraft: (T) -> T,
        applyReset: () -> Unit,
    ) {
        draft.value = clearDraft(draft.value)
        if (!compact) applyReset()
    }

    /**
     * The panel shows no field any more (a resize widened the window until every field is back in
     * the bar, and the draft changed none): an empty panel would only offer an apply button that does
     * nothing, so it closes. Returns true when it closed or was already closed, so a caller can
     * `return` instead of composing the panel. See [closeWhenEmpty], the composable that calls this
     * from an effect.
     */
    fun closeIfEmpty(hasFields: Boolean): Boolean {
        if (hasFields) return false
        close()
        return true
    }
}
