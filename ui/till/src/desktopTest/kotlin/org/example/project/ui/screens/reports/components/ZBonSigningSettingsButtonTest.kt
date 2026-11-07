package org.example.project.ui.screens.reports.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The closing dialog names the missing signing till and, when the host can, takes the operator to the setting. */
@OptIn(ExperimentalTestApi::class)
class ZBonSigningSettingsButtonTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `a missing signing till offers the shortcut once, and only when the host supplies it`() =
        runComposeUiTest {
            var opened = 0
            var withAction by mutableStateOf(true)
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.ENGLISH) {
                    CreateZBonConfirmDialog(
                        language = Language.ENGLISH,
                        draft = ZBonClosingDraft(readiness = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.NOT_DESIGNATED)),
                        currencyCode = "EUR",
                        onCountChange = {},
                        onTipCheckedChange = { _, _ -> },
                        onTipAmountChange = { _, _ -> },
                        onTipMethodChange = { _, _ -> },
                        onTopUpSourceSelect = {},
                        onTopUpAmountChange = {},
                        onConfirm = {},
                        onDismiss = {},
                        onOpenSigningSettings = if (withAction) ({ opened++ }) else null,
                    )
                }
            }
            onNodeWithText("Set the signing till").assertIsDisplayed().performClick()
            assertEquals(1, opened)
            withAction = false
            waitForIdle()
            onAllNodesWithText("Set the signing till").assertCountEquals(0)
        }
}
