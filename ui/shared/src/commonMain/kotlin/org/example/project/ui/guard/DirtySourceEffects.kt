package org.example.project.ui.guard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Registers this composition as a place unsaved edits can live, for as long as it is composed.
 * [dirty] is the value from the **last composition**: a handler that makes the state clean and leaves
 * in the same event (a delete that navigates away at once) still sees the old value, so such a screen
 * uses the lambda overload, which reads live state at the moment a leave is attempted.
 * [onDiscard] must make the draft really disappear (reset the view model, close the dialog).
 */
@Composable
fun rememberDirtySource(
    dirty: Boolean,
    onDiscard: () -> Unit,
): DirtySource {
    val dirtyNow = rememberUpdatedState(dirty)
    return rememberDirtySource(dirty = { dirtyNow.value }, onDiscard = onDiscard)
}

/** Like the [Boolean] overload, but [dirty] is read when a leave is attempted, so it must read live (snapshot) state. */
@Composable
fun rememberDirtySource(
    dirty: () -> Boolean,
    onDiscard: () -> Unit,
): DirtySource {
    val guard = LocalLeaveGuard.current
    val dirtyNow = rememberUpdatedState(dirty)
    val discardNow = rememberUpdatedState(onDiscard)
    val source = remember { DirtySource({ dirtyNow.value() }, { discardNow.value() }) }
    DisposableEffect(guard, source) {
        guard.register(source)
        onDispose { guard.unregister(source) }
    }
    return source
}

/**
 * For dialogs and sheets: the lambda to use as `onDismiss` **and** as the Cancel button's click.
 * Clean: runs [onClose] at once. Dirty: asks, and [onClose] runs only on Discard.
 */
@Composable
fun rememberGuardedDismiss(
    dirty: Boolean,
    onClose: () -> Unit,
): () -> Unit {
    val guard = LocalLeaveGuard.current
    val source = rememberDirtySource(dirty, onClose)
    return remember(guard, source) { { guard.requestClose(source) } }
}
