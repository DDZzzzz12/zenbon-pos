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
class PosAutoSizeTextTest {
    @Test
    fun rendersProvidedTextInNarrowBox() =
        runComposeUiTest {
            setContent {
                PosAutoSizeText(
                    text = "1.234.567,89 €",
                    modifier = Modifier.width(60.dp),
                )
            }
            onNodeWithText("1.234.567,89 €").assertIsDisplayed()
        }
}
