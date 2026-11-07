package org.example.project.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Crossing a width breakpoint must not remount the till's screen (browser pass 2, R10).
 *
 * The shell used to call its content in a separate branch per layout, so a tablet rotation or a
 * browser resize across 600/840 dp disposed the whole nested NavHost and built it again: every
 * screen's `LaunchedEffect`s re-ran (the Finanzen screen refetched books, balance, entries and the
 * open PDF) and every `remember` was lost (the PDF went back to page 1).
 */
@OptIn(ExperimentalTestApi::class)
class PosNavShellFrameTest {
    @Test
    fun `switching between the phone and the rail layout keeps the content mounted`() =
        runComposeUiTest {
            var compact by mutableStateOf(false)
            var mounts = 0
            var disposals = 0
            var remembered: Any? = null
            val seen = mutableSetOf<Any>()

            setContent {
                PosNavShellFrame(
                    compact = compact,
                    rail = { Box {} },
                    bottomChrome = { Box {} },
                ) {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposals++ }
                    }
                    remembered = remember { Any() }
                    remembered?.let { seen += it }
                }
            }
            waitForIdle()

            compact = true
            waitForIdle()
            compact = false
            waitForIdle()
            compact = true
            waitForIdle()

            assertEquals(1, mounts, "the content must be mounted once, not once per layout")
            assertEquals(0, disposals, "a layout change must not dispose the content")
            assertEquals(1, seen.size, "remembered state must survive the layout change")
        }
}
