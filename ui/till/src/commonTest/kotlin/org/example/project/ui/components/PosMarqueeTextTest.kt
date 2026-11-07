package org.example.project.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PosMarqueeTextTest {
    @Test
    fun rendersProvidedText() =
        runComposeUiTest {
            setContent {
                PosMarqueeText(
                    text = "A very long dialog header that would overflow",
                    modifier = Modifier.width(80.dp),
                )
            }
            onNodeWithText("A very long dialog header that would overflow").assertIsDisplayed()
        }
}
