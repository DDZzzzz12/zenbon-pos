package org.example.project.ui.screens.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import org.example.project.ui.screens.dashboard.dialogs.PosDeleteTableDialog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cancel dialogs: a reason is required, picked from chips, typed only for "Other". */
@OptIn(ExperimentalTestApi::class)
class ReasonDialogsTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private val table =
        RTable(
            id = 7,
            tableNumber = "7",
            seats = 4,
            locationTypeId = 1,
            locationTypeName = "Indoor",
            tableStatus = "OCCUPIED",
            currentSessionId = 1,
            gridX = 0,
            gridY = 0,
            rotation = 0f,
        )

    @Test
    fun `cancel items - confirm without a reason does not submit and shows the hint`() =
        runComposeUiTest {
            val sent = mutableListOf<String>()
            setContent { germanCancelItems { sent += it } }

            onNodeWithText("Bestätigen").performClick()

            assertEquals(emptyList(), sent)
            onNodeWithText("Bitte einen Grund auswählen").assertIsDisplayed()
        }

    @Test
    fun `cancel items - a chip submits its German stored text`() =
        runComposeUiTest {
            val sent = mutableListOf<String>()
            setContent { germanCancelItems { sent += it } }

            onNodeWithText("Küchenfehler").performClick()
            onNodeWithText("Bestätigen").performClick()

            assertEquals(listOf("Küchenfehler"), sent)
        }

    @Test
    fun `cancel items - the details field is absent until Other is chosen`() =
        runComposeUiTest {
            setContent { germanCancelItems { } }

            onNodeWithText("Details").assertDoesNotExist()
            onNodeWithText("Sonstiges").performClick()
            onNodeWithText("Details").assertIsDisplayed()
        }

    @Test
    fun `cancel items - Other with typed text submits the trimmed text, blank does not`() =
        runComposeUiTest {
            val sent = mutableListOf<String>()
            setContent { germanCancelItems { sent += it } }

            onNodeWithText("Sonstiges").performClick()
            onNodeWithText("Bestätigen").performClick()
            assertEquals(emptyList(), sent)

            onNodeWithText("Details").performTextInput("  Gast war betrunken ")
            onNodeWithText("Bestätigen").performClick()
            assertEquals(listOf("Gast war betrunken"), sent)
        }

    @Test
    fun `delete table - confirm without a reason does not submit, a chip does`() =
        runComposeUiTest {
            val sent = mutableListOf<String>()
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN, LocalVirtualKeyboard provides VirtualKeyboardController()) {
                    PosDeleteTableDialog(table = table, onConfirm = { sent += it }, onDismiss = {})
                }
            }

            onNodeWithText("Löschen").performClick()
            assertEquals(emptyList(), sent)

            onNodeWithText("Falscher Tisch").performClick()
            onNodeWithText("Löschen").performClick()
            assertEquals(listOf("Falscher Tisch"), sent)
        }

    @androidx.compose.runtime.Composable
    private fun germanCancelItems(onConfirm: (String) -> Unit) {
        CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN, LocalVirtualKeyboard provides VirtualKeyboardController()) {
            PosCancelItemsDialog(
                itemCount = 1,
                hint = CancelAuthorizationHint.NONE,
                authorizingManager = null,
                onConfirm = onConfirm,
                onDismiss = {},
            )
        }
    }
}
