package org.example.project.admin.core.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * The slot at the end of the phone top bar. The shell provides it only where that bar exists; a
 * screen fills it with [RegisterTopBarAction] and the entry goes when the screen leaves, so an
 * action never outlives the destination it belongs to.
 */
class AdminTopBarActions {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

/** `null` outside the phone shell — callers then keep the action inline. */
val LocalAdminTopBarActions = compositionLocalOf<AdminTopBarActions?> { null }

/** Puts [content] into the phone top bar while this call is in the composition. No-op without a bar. */
@Composable
fun RegisterTopBarAction(content: @Composable () -> Unit) {
    val slot = LocalAdminTopBarActions.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(slot) {
        slot.content = { latest() }
        onDispose { slot.content = null }
    }
}
