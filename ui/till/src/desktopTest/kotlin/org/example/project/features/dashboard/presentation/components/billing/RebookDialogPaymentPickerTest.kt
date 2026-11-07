package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.money.Decimal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Zahlartwechsel adds payments through the table billing screen's own `PaymentTypePickerDialog`
 * — not a separate bottom sheet — so every voucher source the billing screen takes is available,
 * a LEGACY voucher (number + amount, no validation) included.
 */
@OptIn(ExperimentalTestApi::class)
class RebookDialogPaymentPickerTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private val cash = PaymentType(id = 1L, name = "BAR", isActive = true)
    private val voucher = PaymentType(id = 3L, name = "GUTSCHEIN", isActive = true)

    private val sources =
        listOf(
            VoucherSource(type = VoucherSourceType.INTERNAL, name = "Hausgutschein", enabled = true),
            VoucherSource(type = VoucherSourceType.LEGACY, name = "Altgutschein", enabled = true),
        )

    private val bill =
        BillDto.BillResponseDto(
            billId = 42L,
            employeeId = "emp-1",
            employeeNumber = "07",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 500L,
            tableNumber = "12",
            billNumber = "RE-42",
            billAmount = "23.00",
            paidAmount = "23.00",
            tips = "0.00",
            startTime = "2026-09-29T12:00:00",
            endTime = "2026-09-29T12:10:00",
            orderItems = emptyList(),
            payments =
                listOf(
                    PaymentDto.PaymentResponse(
                        id = 1L,
                        amount = "23.00",
                        paymentTypeId = cash.id,
                        paymentTypeName = "BAR",
                        billId = 42L,
                        createdAt = "2026-09-29T12:10:00",
                    ),
                ),
            isRebookable = true,
        )

    private fun german(key: StringKey) = TranslationRegistry.get(key, Language.GERMAN)

    @Test
    fun `a legacy voucher can be added and is confirmed with source LEGACY`() =
        runComposeUiTest {
            var confirmed: Pair<List<PaymentEntry>, Decimal>? = null
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.GERMAN,
                    LocalVirtualKeyboard provides VirtualKeyboardController(),
                ) {
                    RebookDialog(
                        bill = bill,
                        language = Language.GERMAN,
                        paymentTypes = listOf(cash, voucher),
                        voucherConfig = VoucherConfig(enabled = true, sources = sources),
                        enabledVoucherSources = sources,
                        isValidatingVoucher = false,
                        validatedVoucher = null,
                        voucherValidationError = null,
                        mayLowerTip = false,
                        onValidateVoucher = { _, _, _ -> error("a legacy voucher is never validated") },
                        onClearVoucherValidation = {},
                        onConfirm = { entries, tip -> confirmed = entries to tip },
                        onDismiss = {},
                    )
                }
            }

            // Drop the cash leg; the blank amount field then adds the whole rest (23,00).
            onNodeWithText(german(CommonKeys.delete)).performClick()
            onNodeWithText(german(BillReceiptKeys.changePaymentAddPayment)).performClick()
            waitForIdle()

            // The billing screen's picker: the voucher card, then the legacy source.
            onNodeWithText(german(BillingKeys.voucherLabel)).performClick()
            waitForIdle()
            onNodeWithText("Altgutschein").performClick()
            onNode(hasSetTextAction() and hasText(german(AddPaymentKeys.voucherNumberLabel))).performTextInput("alt-77")
            onNode(hasSetTextAction() and hasText(german(AddPaymentKeys.amountLabel))).performTextInput("23.00")
            onNodeWithText(german(AddPaymentKeys.addButton)).performClick()
            waitForIdle()

            onNodeWithText(german(BillReceiptKeys.changePaymentConfirm)).performClick()
            waitForIdle()

            val (entries, tip) = confirmed ?: error("the balanced voucher tender must be confirmable")
            assertEquals(Decimal.ZERO, tip)
            val leg = entries.single()
            assertEquals(voucher.id, leg.paymentTypeId)
            assertEquals(Decimal.parse("23.00"), leg.amount)
            assertEquals("ALT-77", leg.voucherNumber)
            assertEquals(VoucherSourceType.LEGACY, leg.voucherSource)
        }

    @Test
    fun `the next payment's amount follows the billing numpad rule`() {
        val rest = Decimal.parse("12.50")
        assertEquals(rest, rebookNextPaymentAmount("", rest), "blank takes the rest")
        assertEquals(Decimal.parse("5.00"), rebookNextPaymentAmount("5,00", rest), "a typed amount is taken as typed")
        assertNull(rebookNextPaymentAmount("", Decimal.ZERO), "nothing left, nothing to add")
        assertNull(rebookNextPaymentAmount("0", rest))
        assertNull(rebookNextPaymentAmount("abc", rest))
    }
}
