package org.example.project.ui.screens.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.example.project.features.notifications.presentation.state.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell's notification-stream connect trigger (F57).
 *
 * The browser build's window lifecycle reaches RESUMED only while `document.hasFocus()`; a page
 * loaded without focus (a reload in a background tab, or with focus elsewhere) stays STARTED until
 * the next focus event. A trigger keyed on ON_RESUME therefore never opened the station's stream
 * after a reload — "Getrennt · TSE Unbekannt" while the grid loaded fine over REST.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationStreamLifecycleEffectTest {
    private class FakeOwner(
        state: Lifecycle.State,
    ) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun connectsFor(
        lifecycleState: Lifecycle.State,
        connectionState: ConnectionState,
    ): Int {
        var connects = 0
        runComposeUiTest {
            val owner = FakeOwner(lifecycleState)
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    NotificationStreamLifecycleEffect(connectionState) { connects++ }
                }
            }
            waitForIdle()
        }
        return connects
    }

    @Test
    fun `a visible but unfocused window still opens the stream`() {
        assertEquals(1, connectsFor(Lifecycle.State.STARTED, ConnectionState.Disconnected))
    }

    @Test
    fun `a focused window opens the stream once`() {
        assertEquals(1, connectsFor(Lifecycle.State.RESUMED, ConnectionState.Disconnected))
    }

    @Test
    fun `a failed stream is reopened`() {
        assertEquals(1, connectsFor(Lifecycle.State.STARTED, ConnectionState.Failed))
    }

    @Test
    fun `a live or connecting stream is left alone`() {
        assertEquals(0, connectsFor(Lifecycle.State.RESUMED, ConnectionState.Connected))
        assertEquals(0, connectsFor(Lifecycle.State.RESUMED, ConnectionState.Connecting))
    }

    @Test
    fun `a hidden window does not connect`() {
        assertEquals(0, connectsFor(Lifecycle.State.CREATED, ConnectionState.Disconnected))
    }
}
