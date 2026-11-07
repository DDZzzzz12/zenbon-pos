package org.example.project.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * The till shell's two arrangements around one [content] slot: a phone column with the
 * [bottomChrome] under the content, or a [rail] beside it.
 *
 * **The content is moved between the arrangements, never rebuilt** (browser pass 2, R10). Calling
 * it in each branch made a width crossing — a tablet rotation, a browser resize — dispose the whole
 * nested NavHost and compose it again: every screen re-ran its `LaunchedEffect`s (the Finanzen
 * screen refetched books, balance, entries and the open PDF) and lost its `remember`ed state (the
 * PDF went back to page 1). [movableContentOf] carries the composition, its state and its effects
 * across to the other branch instead. It is called exactly once per composition, and reads the
 * latest [content] through [rememberUpdatedState], so a new lambda recomposes it without a move.
 * adminApp's single movable NavHost (`AdminApp.kt`) is the same fix.
 */
@Composable
internal fun PosNavShellFrame(
    compact: Boolean,
    rail: @Composable () -> Unit,
    bottomChrome: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }

    if (compact) {
        Column(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f)) {
                movableContent()
            }
            bottomChrome()
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                rail()
                Column(modifier = Modifier.weight(1f)) {
                    movableContent()
                }
            }
        }
    }
}
