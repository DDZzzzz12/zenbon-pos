package org.example.project.core.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import kotlin.random.Random
import kotlin.time.TimeSource

private const val TICK_MS = 500L

/**
 * Shows [content] only in the tab that owns the browser station; a second tab of the same
 * origin gets a full-screen notice instead. Outside the browser it is transparent.
 *
 * The tab id comes from [Random], never `crypto.randomUUID`, which does not exist on a plain-http LAN origin.
 */
@Composable
fun SingleTabGate(content: @Composable () -> Unit) {
    if (!isBrowserClient) {
        content()
        return
    }
    val guard =
        remember {
            val origin = TimeSource.Monotonic.markNow()
            SingleTabGuard(
                messenger = createTabMessenger(),
                clock = { origin.elapsedNow().inWholeMilliseconds },
                tabId = Random.nextLong(Long.MAX_VALUE),
            )
        }
    LaunchedEffect(guard) {
        guard.start()
        while (true) {
            delay(TICK_MS)
            guard.tick()
        }
    }
    val state by guard.state.collectAsState()
    if (state == TabState.Duplicate) {
        // raw Material 3 by design: a bare full-screen notice that replaces the whole app
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = str(CommonKeys.openInAnotherTab),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        content()
    }
}
