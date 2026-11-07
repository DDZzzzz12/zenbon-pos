package org.example.project.ui.screens.billing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import org.example.project.money.Decimal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * With several voucher sources enabled the voucher page shows the FIRST source's fields; the picker
 * must have that source selected too. It used to preselect only a single source, so with LEGACY
 * listed first the legacy fields were on screen while "Hinzufügen" stayed disabled.
 */
@OptIn(ExperimentalTestApi::class)
class PaymentTypePickerDialogVoucherSourceTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun german(key: StringKey) = TranslationRegistry.get(key, Language.GERMAN)

    @Test
    fun `the first enabled voucher source is preselected and a legacy voucher can be added at once`() =
        runComposeUiTest {
            var added: Pair<String, VoucherSourceType>? = null
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.GERMAN,
                    LocalVirtualKeyboard provides VirtualKeyboardController(),
                ) {
                    PaymentTypePickerDialog(
                        amount = Decimal.parse("12.00"),
                        paymentTypes =
                            listOf(
                                PaymentType(id = 1L, name = "BAR", isActive = true),
                                PaymentType(id = 3L, name = "GUTSCHEIN", isActive = true),
                            ),
                        language = Language.GERMAN,
                        onPaymentConfirmed = { _, _ -> },
                        onDismiss = {},
                        isVoucherEnabled = true,
                        enabledVoucherSources =
                            listOf(
                                VoucherSource(type = VoucherSourceType.LEGACY, name = "Altgutschein", enabled = true),
                                VoucherSource(type = VoucherSourceType.INTERNAL, name = "Hausgutschein", enabled = true),
                            ),
                        onVoucherConfirmed = { _, _, number, source -> added = number to source },
                    )
                }
            }

            onNodeWithText(german(BillingKeys.voucherLabel)).performClick()
            waitForIdle()
            // No chip tapped: the legacy fields shown first are the selected source.
            onNode(hasSetTextAction() and hasText(german(AddPaymentKeys.voucherNumberLabel))).performTextInput("ALT-5")
            onNode(hasSetTextAction() and hasText(german(AddPaymentKeys.amountLabel))).performTextInput("12.00")
            onNode(hasClickAction() and hasText(german(AddPaymentKeys.addButton))).assertIsEnabled().performClick()
            waitForIdle()

            assertEquals("ALT-5" to VoucherSourceType.LEGACY, added)
        }
}
