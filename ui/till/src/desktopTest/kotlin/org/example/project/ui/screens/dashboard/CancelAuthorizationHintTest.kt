package org.example.project.ui.screens.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QA emu22 NOTE-ee: the station's Storno dialog said „Erfordert Manager-Autorisierung.“ while a
 * manager was signed in and the Storno executed at once. It now names the authorizing manager.
 */
@OptIn(ExperimentalTestApi::class)
class CancelAuthorizationHintTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `an active manager session names the manager`() {
        assertEquals(CancelAuthorizationHint.AUTHORIZED_BY, CancelAuthorizationHint.of("M001", holdsItemCancel = true))
    }

    @Test
    fun `own capability needs no hint, missing capability still asks for authorization`() {
        assertEquals(CancelAuthorizationHint.NONE, CancelAuthorizationHint.of(null, holdsItemCancel = true))
        assertEquals(CancelAuthorizationHint.REQUIRED, CancelAuthorizationHint.of(null, holdsItemCancel = false))
        assertEquals(CancelAuthorizationHint.REQUIRED, CancelAuthorizationHint.of(" ", holdsItemCancel = false))
    }

    @Test
    fun `the dialog shows who authorizes under a manager sign-in`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN, LocalVirtualKeyboard provides VirtualKeyboardController()) {
                    PosCancelItemsDialog(
                        itemCount = 1,
                        hint = CancelAuthorizationHint.AUTHORIZED_BY,
                        authorizingManager = "M001",
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
            onNodeWithText("Freigabe durch M001.").assertIsDisplayed()
            onNodeWithText("Erfordert Manager-Autorisierung.").assertDoesNotExist()
        }

    @Test
    fun `the dialog keeps the authorization hint without a manager`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN, LocalVirtualKeyboard provides VirtualKeyboardController()) {
                    PosCancelItemsDialog(
                        itemCount = 1,
                        hint = CancelAuthorizationHint.REQUIRED,
                        authorizingManager = null,
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
            onNodeWithText("Erfordert Manager-Autorisierung.").assertIsDisplayed()
        }
}
