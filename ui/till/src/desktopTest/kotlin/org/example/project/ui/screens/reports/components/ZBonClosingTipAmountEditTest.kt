package org.example.project.ui.screens.reports.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import org.example.project.features.reports.presentation.components.zBon.ClosingTipPayoutSection
import org.example.project.features.reports.presentation.state.ClosingTipPayoutRow
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.Timeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F64: the till froze ("App reagiert nicht") while the operator lowered the prefilled tip payout in
 * "Z-Bon erstellen" from "3,00" to "1". Drives the real dialog against the real [ZBonViewModel] and
 * edits the amount both ways a till does — the physical keyboard and the on-screen keyboard — and
 * asserts that every keystroke settles in a bounded number of compositions with the operator's text
 * left exactly as typed.
 *
 * The drawer expects 2.00 and Mia's 3.00 tip is paid in cash, so "3,00" opens a 1.00 shortfall and
 * the top-up section; emptying the field closes it, and "1" stays inside the drawer.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ZBonClosingTipAmountEditTest {
    private val mia =
        ZBonDto.OpenPeriodTipDto(
            employeeId = "11111111-1111-1111-1111-111111111111",
            employeeNumber = "W1",
            employeeName = "Mia",
            tipTotal = "3.0000",
            cashTip = "3.0000",
            cardTip = "0.0000",
        )

    private val tresor =
        CashBookDto.ResponseDto(
            id = 2L,
            name = "Tresor",
            isFiscal = false,
            datevAccount = "1010",
            closeInterval = CashBookCloseInterval.MANUAL,
            isActive = true,
            sortOrder = 2,
            currentBalance = "50.00",
            currency = "EUR",
        )

    private lateinit var vm: ZBonViewModel

    @BeforeTest
    fun setUp() {
        TranslationRegistry.initialize(defaultTranslations())
        // The fakes answer synchronously, so the dialog's three loads complete inside openClosingDialog.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val zBonApi = FakeZBonApiClient().apply { openPeriodTipsResult = listOf(mia) }
        val kassenbuchApi =
            FakeKassenbuchApiClient().apply {
                getCashCountExpectationResult =
                    KassenbuchDto.CashCountExpectationDto(bookBalance = "2.00", unbookedCashTakings = "0.00", expectedCash = "2.00")
            }
        val cashBookApi = FakeCashBookApiClient().apply { listBooksResult = listOf(tresor) }
        vm = ZBonViewModel(zBonApi, kassenbuchApi, LanguageManager(), cashBookApi)
        vm.openClosingDialog(includeTipPayouts = true)
        Dispatchers.resetMain()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun german(key: StringKey) = TranslationRegistry.get(key, Language.GERMAN)

    private fun amount(): String = assertNotNull(vm.zBonState.value.closingDraft).tipRows.single().amount

    /** Composes the dialog and returns a counter of its compositions. */
    private fun ComposeUiTest.showDialog(keyboard: VirtualKeyboardController): IntArray {
        val compositions = IntArray(1)
        setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides Language.GERMAN,
                LocalVirtualKeyboard provides keyboard,
            ) {
                val state by vm.zBonState.collectAsState()
                SideEffect { compositions[0]++ }
                CreateZBonConfirmDialog(
                    language = Language.GERMAN,
                    draft = assertNotNull(state.closingDraft),
                    currencyCode = "EUR",
                    onCountChange = vm::updateClosingCount,
                    onTipCheckedChange = vm::setClosingTipChecked,
                    onTipAmountChange = vm::updateClosingTipAmount,
                    onTipMethodChange = vm::setClosingTipMethod,
                    onTopUpSourceSelect = vm::selectClosingTopUpSource,
                    onTopUpAmountChange = vm::updateClosingTopUpAmount,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        return compositions
    }

    private fun ComposeUiTest.assertSettles(
        compositions: IntArray,
        step: String,
        action: () -> Unit,
    ) {
        val before = compositions[0]
        action()
        waitForIdle()
        val spent = compositions[0] - before
        assertTrue(spent <= MAX_COMPOSITIONS_PER_KEYSTROKE, "$step recomposed the dialog $spent times")
    }

    @Test
    @Timeout(60)
    fun `lowering the payout with the physical keyboard keeps the typed text and settles`() =
        runComposeUiTest {
            assertEquals("3,00", amount())
            val compositions = showDialog(VirtualKeyboardController())
            val field = onNode(hasSetTextAction() and hasText(german(TipPayoutKeys.closingAmountLabel)))
            field.performClick()
            waitForIdle()

            assertSettles(compositions, "clearing the field") { field.performTextClearance() }
            assertEquals("", amount())
            assertSettles(compositions, "typing 1") { field.performTextInput("1") }

            assertEquals("1", amount(), "the operator's text is kept, never rewritten to \"1,00\"")
            val draft = assertNotNull(vm.zBonState.value.closingDraft)
            assertEquals(TipPayoutAmountCheck.VALID, draft.tipRows.single().check)
            assertEquals(null, draft.shortfall, "1.00 fits in the 2.00 drawer")
            assertEquals("1,00", draft.countPlaceholder, "the count placeholder follows the payout: 2.00 − 1.00")
            assertTrue(draft.canConfirm)
        }

    @Test
    @Timeout(60)
    fun `lowering the payout on the on-screen keyboard keeps the typed text and settles`() =
        runComposeUiTest {
            val keyboard = VirtualKeyboardController().apply { hasPhysicalKeyboard = false }
            val compositions = showDialog(keyboard)
            onNode(hasSetTextAction() and hasText(german(TipPayoutKeys.closingAmountLabel))).performClick()
            waitForIdle()
            assertTrue(keyboard.isVisible, "tapping the field opens the on-screen keyboard")

            repeat(4) { i -> assertSettles(compositions, "backspace ${i + 1}") { keyboard.onBackspace() } }
            assertEquals("", amount())
            assertSettles(compositions, "typing 1") { keyboard.onKeyPress("1") }

            assertEquals("1", amount())
            assertEquals("1", keyboard.textFieldValue.text, "the keyboard and the row agree on the text")
            assertTrue(keyboard.isVisible, "the field kept its focus through the whole edit")
            assertTrue(assertNotNull(vm.zBonState.value.closingDraft).canConfirm)
        }

    /**
     * F65: QA read "Bestätigen" as still enabled while the field was empty. Drives the real dialog:
     * an empty field disables the button and shows the "enter an amount or untick" hint, and a
     * half-typed "1," is 1,00 and enables it again.
     */
    @Test
    @Timeout(60)
    fun `an empty payout disables Bestaetigen with a hint and a trailing comma enables it`() =
        runComposeUiTest {
            showDialog(VirtualKeyboardController())
            val field = onNode(hasSetTextAction() and hasText(german(TipPayoutKeys.closingAmountLabel)))
            val confirm = onNode(hasClickAction() and hasText(german(CommonKeys.confirm)))
            confirm.assertIsEnabled()

            field.performTextClearance()
            waitForIdle()
            confirm.assertIsNotEnabled()
            onNode(hasText(german(TipPayoutKeys.closingAmountMissing)), useUnmergedTree = true).assertExists()

            field.performTextInput("1,")
            waitForIdle()
            assertEquals("1,", amount())
            confirm.assertIsEnabled()
        }

    /**
     * The race itself, made deterministic: the ViewModel's echo of a keystroke reaches the dialog only
     * when the StateFlow collector runs, so the field must show what the operator typed even while
     * its row still holds the previous text. Before F64 the field rendered `row.amount`, i.e. "3,00"
     * here, and pushed it back over the operator's input.
     */
    @Test
    @Timeout(60)
    fun `the field shows the typed text before the ViewModel has echoed it`() =
        runComposeUiTest {
            val staleRow =
                ClosingTipPayoutRow(employeeId = mia.employeeId, employeeName = "Mia", total = "3.00", cash = "3.00", card = "0.00", amount = "3,00")
            val sent = mutableListOf<String>()
            val keyboard = VirtualKeyboardController()
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.GERMAN,
                    LocalVirtualKeyboard provides keyboard,
                ) {
                    // The row never changes: every keystroke is still "in flight" to the ViewModel.
                    ClosingTipPayoutSection(
                        rows = listOf(staleRow),
                        currencyCode = "EUR",
                        onCheckedChange = { _, _ -> },
                        onAmountChange = { _, text -> sent += text },
                    )
                }
            }
            val field = onNode(hasSetTextAction() and hasText(german(TipPayoutKeys.closingAmountLabel)))
            field.performTextClearance()
            field.performTextInput("1")
            // While focused the desktop field renders the on-screen keyboard's buffer; once released it
            // renders its own value — which, before F64, was the stale row.
            keyboard.dismiss()
            waitForIdle()

            onNode(hasSetTextAction() and hasText("1", substring = false)).assertExists("the field keeps the operator's \"1\"")
            assertEquals(listOf("", "1"), sent, "the ViewModel is told every change")
        }

    private companion object {
        /** A keystroke changes the draft once; a handful of follow-up frames is normal, a loop is not. */
        const val MAX_COMPOSITIONS_PER_KEYSTROKE = 5
    }
}
