package org.example.project.ui.guard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Registry of [DirtySource]s plus the decision "may this leave run now?".
 * UI thread only (clicks, effects). [pending] is snapshot state so the host recomposes.
 */
class LeaveGuard(
    private val enabled: Boolean = true,
) {
    private val sources = mutableListOf<DirtySource>()

    var pending: PendingLeave? by mutableStateOf(null)
        private set

    /** True when any registered source holds unsaved edits (drives `beforeunload`). */
    val isDirty: Boolean get() = enabled && sources.any { it.isDirty }

    fun register(source: DirtySource) {
        if (enabled && source !in sources) sources += source
    }

    fun unregister(source: DirtySource) {
        sources -= source
    }

    /** Runs [leave] now when nothing is dirty; otherwise asks first. Ignored while a prompt is open. */
    fun requestLeave(leave: () -> Unit) {
        if (pending != null) return
        val dirty = if (enabled) sources.filter { it.isDirty } else emptyList()
        if (dirty.isEmpty()) {
            leave()
            return
        }
        pending = PendingLeave(dirty, leave)
    }

    /** Dialog close: asks about [source] alone; clean means close at once. */
    fun requestClose(source: DirtySource) {
        if (pending != null) return
        if (!enabled || !source.isDirty) {
            source.discard()
            return
        }
        pending = PendingLeave(listOf(source)) {}
    }

    fun keepEditing() {
        pending = null
    }

    /** Discards every dirty source in scope, then runs the leave, exactly once. */
    fun discard() {
        val p = pending ?: return
        pending = null
        p.sources.forEach { it.discard() }
        p.then()
    }

    /** Sign-out / tests: forget any open prompt. Registered sources unregister themselves on disposal. */
    fun clear() {
        pending = null
    }

    companion object {
        /** No provider above: every leave runs at once. */
        val Inert = LeaveGuard(enabled = false)
    }
}
