package org.example.project.core.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)

    DisposableEffect(enabled) {
        val dispatcher = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener =
            KeyEventDispatcher { event ->
                // Intercept ESCAPE key press
                if (enabled && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
                    currentOnBack()
                    true // Consume the event so it doesn't propagate
                } else {
                    false
                }
            }

        if (enabled) {
            dispatcher.addKeyEventDispatcher(listener)
        }

        onDispose {
            dispatcher.removeKeyEventDispatcher(listener)
        }
    }
}
