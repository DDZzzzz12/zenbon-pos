package org.example.project.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Occupied tables expose a visible "Details / Bill" button: long-press and right-click are not
 * discoverable on a mouse- or touch-only browser station.
 */
@OptIn(ExperimentalTestApi::class)
class PosTableCardTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `an occupied card shows a details button that fires the long-click action once`() =
        runComposeUiTest {
            var details = 0
            var clicks = 0
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.ENGLISH) {
                    PosTableCard(
                        tableNumber = "T1",
                        statusColor = Color.Red,
                        statusText = "Occupied",
                        showDetailsAction = true,
                        onClick = { clicks++ },
                        onLongClick = { details++ },
                    )
                }
            }
            onNodeWithContentDescription("Details / Bill").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, details)
            assertEquals(0, clicks, "the button must not also trigger the card's own click")
        }

    @Test
    fun `a free card renders no details button`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.ENGLISH) {
                    PosTableCard(
                        tableNumber = "T2",
                        statusColor = Color.Green,
                        statusText = "Free",
                        onLongClick = {},
                    )
                }
            }
            onNodeWithContentDescription("Details / Bill").assertDoesNotExist()
        }

    @Test
    fun `at a large font scale the card grows and no text is cut off`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.ENGLISH,
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    PosTableCard(
                        tableNumber = "T12",
                        statusColor = Color.Red,
                        statusText = "Occupied",
                        secondaryText = "K003",
                        openAmount = "24.50",
                        showDetailsAction = true,
                    )
                }
            }
            val card = onRoot().getBoundsInRoot()
            val status = onNodeWithText("Occupied").getBoundsInRoot()
            assertTrue(status.bottom <= card.bottom, "status text ($status) must stay inside the card ($card)")
            assertTrue(card.height > 90.dp, "the card must grow beyond its 90 dp minimum, was ${card.height}")
        }
}
