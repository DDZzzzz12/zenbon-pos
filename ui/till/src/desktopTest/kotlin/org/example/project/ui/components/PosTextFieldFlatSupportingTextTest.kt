package org.example.project.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import kotlin.test.Test

/**
 * e2e 10.8: the flat [PosTextField] (adminApp's default appearance) silently dropped
 * `supportingText`, so the permission-group editor's Code hint "Wird gespeichert als: …" never
 * rendered. Composed for the flat and the outlined appearance, so a variant that drops the slot
 * again fails here.
 */
@OptIn(ExperimentalTestApi::class)
class PosTextFieldFlatSupportingTextTest {
    private val hint = "Wird gespeichert als: EXPORT_LESER. Nachträglich nicht änderbar."

    /** The desktop field reads the virtual-keyboard registry, which the app root provides. */
    @Composable
    private fun WithKeyboard(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalVirtualKeyboard provides VirtualKeyboardController(), content = content)

    private fun assertHintShown(appearance: PosTextFieldAppearance) =
        runComposeUiTest {
            setContent {
                WithKeyboard {
                    PosTextField(
                        value = "Export-Leser",
                        onValueChange = {},
                        label = "Code",
                        supportingText = { Text(hint) },
                        appearance = appearance,
                    )
                }
            }
            onNodeWithText(hint).assertIsDisplayed()
        }

    @Test
    fun `flat appearance renders supportingText`() = assertHintShown(PosTextFieldAppearance.Flat)

    @Test
    fun `outlined appearance renders supportingText`() = assertHintShown(PosTextFieldAppearance.Outlined)

    @Test
    fun `flat appearance still renders the error message`() =
        runComposeUiTest {
            setContent {
                WithKeyboard {
                    PosTextField(
                        value = "",
                        onValueChange = {},
                        label = "Code",
                        errorMessage = "Pflichtfeld",
                        appearance = PosTextFieldAppearance.Flat,
                    )
                }
            }
            onNodeWithText("Pflichtfeld").assertIsDisplayed()
        }
}
