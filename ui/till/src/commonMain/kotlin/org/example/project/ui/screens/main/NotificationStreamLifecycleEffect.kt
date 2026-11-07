package org.example.project.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.example.project.features.notifications.presentation.state.ConnectionState

/**
 * Opens the notification stream when the shell becomes visible with no stream up.
 *
 * On the wasm till/station and on Android's activity this is the only connect trigger in the UI
 * (desktop also connects from `NotificationViewModel.init`, Android from its notification service).
 *
 * Keyed on ON_START, not ON_RESUME (F57): the browser window reaches RESUMED only while
 * `document.hasFocus()`, so a page loaded without focus — a reload in a background tab, or with
 * focus in another window — stayed STARTED and never opened the stream until the next focus event.
 * Visible is enough to want live updates; every platform passes ON_START on its way to RESUMED.
 */
@Composable
internal fun NotificationStreamLifecycleEffect(
    connectionState: ConnectionState,
    onConnect: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (connectionState == ConnectionState.Disconnected || connectionState == ConnectionState.Failed) {
            onConnect()
        }
    }
}
