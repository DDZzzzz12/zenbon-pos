package org.example.project.ui.screens.auth.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * QA emu22 NOTE-ff: the station's manager sign-in entry in the rail was a bare lock icon with an
 * empty content description. It now carries a visible, localized label and the same description.
 */
@OptIn(ExperimentalTestApi::class)
class RequestSessionButtonLabelTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `the compact rail button is labelled and described`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    RequestSessionButton(onClick = {}, language = Language.GERMAN, compact = true)
                }
            }
            onNodeWithText("Manager anmelden").assertIsDisplayed()
            onNodeWithContentDescription("Manager anmelden").assertIsDisplayed()
        }
}
