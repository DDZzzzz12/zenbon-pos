package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.money.Decimal
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * 2026-09-18 e2e finding #5: on a 1280×800 tablet the payment panel showed only the numpad —
 * the typed amount, the paid/remaining line and the payment cards were squeezed to zero height.
 * The list now takes a third of the height below the header by weight; two one-line payments must
 * fit in that share even at the worst-case height.
 *
 * 380 dp is the expanded layout's right column; 330 dp is the height that column gets on a
 * 1280×800 / 213 dpi tablet once the app bar, TSE banner, status bar and confirm button are laid out.
 */
@OptIn(ExperimentalTestApi::class)
class PosBillingPaymentPanelLayoutTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private val cash = PaymentEntry(id = "p1", paymentTypeId = 1, paymentTypeName = "BAR", amount = Decimal.parse("16.00"))
    private val card = PaymentEntry(id = "p2", paymentTypeId = 2, paymentTypeName = "EC-KARTE", amount = Decimal.parse("5.00"))

    private fun composePanel(
        heightDp: Int,
        input: String,
        payments: List<PaymentEntry>,
    ) = runComposeUiTest {
        setContent {
            Box(Modifier.size(380.dp, heightDp.dp)) {
                PosBillingPaymentPanel(
                    numpadInput = input,
                    paidAmount = Decimal.parse("16.00"),
                    remainingAmount = Decimal.parse("37.60"),
                    totalAmount = Decimal.parse("53.60"),
                    payments = payments,
                    onNumpadKey = {},
                    onNumpadBackspace = {},
                    onNumpadClear = {},
                    onEnterPressed = {},
                    onRemovePayment = {},
                )
            }
        }
        onNodeWithText(input).assertIsDisplayed()
        onNodeWithText("37,60", substring = true).assertIsDisplayed()
        payments.forEach { onNodeWithText(it.paymentTypeName).assertIsDisplayed() }
        if (payments.isNotEmpty()) {
            onAllNodesWithContentDescription(getString(BillingKeys.removePaymentButton, Language.DEFAULT))
                .assertCountEquals(payments.size)
        }
        onNodeWithText("1").assertIsDisplayed() // the numpad is still there
    }

    @Test
    fun `tablet height shows the typed amount, the summary line and two payments`() =
        composePanel(heightDp = 330, input = "53,60", payments = listOf(cash, card))

    @Test
    fun `phone height still shows everything`() = composePanel(heightDp = 560, input = "53,60", payments = listOf(cash, card))

    @Test
    fun `without payments the numpad takes the list's share`() = composePanel(heightDp = 330, input = "53,60", payments = emptyList())

    private val tipPrefix: String
        get() = getString(BillingKeys.tipPortionLabel, Language.DEFAULT).substringBefore("{0}")

    private fun composeSummary(tip: Decimal) =
        runComposeUiTest {
            setContent {
                Box(Modifier.size(380.dp, 560.dp)) {
                    PaymentAmountHeader(
                        numpadInput = "",
                        paidAmount = Decimal.parse("12.00"),
                        remainingAmount = Decimal.ZERO,
                        totalAmount = Decimal.parse("12.00"),
                        tipAmount = tip,
                    )
                }
            }
            if (tip > Decimal.ZERO) {
                onNodeWithText(tipPrefix, substring = true).assertIsDisplayed()
                onNodeWithText("$tipPrefix${tip.toPlainString().replace('.', ',')}", substring = true).assertIsDisplayed()
            } else {
                onNodeWithText(tipPrefix.trim(), substring = true).assertDoesNotExist()
            }
        }

    /** F54 #5: an overpayment turned into a tip is named on the summary line, not hidden in the total. */
    @Test
    fun `an overpayment names its tip portion on the summary line`() = composeSummary(Decimal.parse("2.00"))

    @Test
    fun `negative control - without a tip the summary line says nothing about one`() = composeSummary(Decimal.ZERO)
}
