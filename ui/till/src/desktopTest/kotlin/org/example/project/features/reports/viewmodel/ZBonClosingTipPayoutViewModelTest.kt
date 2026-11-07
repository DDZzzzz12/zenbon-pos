package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.money.Decimal
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Z-Bon erstellen" dialog's tip-payout section (F58): rows checked and prefilled, the cash/card
 * split, the expected cash and count prefill following the checked payouts, the amount cap, and the
 * one request that carries count and payouts together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonClosingTipPayoutViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var zBonApi: FakeZBonApiClient
    private lateinit var kassenbuchApi: FakeKassenbuchApiClient
    private lateinit var cashBookApi: FakeCashBookApiClient
    private lateinit var vm: ZBonViewModel

    private val mia =
        ZBonDto.OpenPeriodTipDto(
            employeeId = "11111111-1111-1111-1111-111111111111",
            employeeNumber = "W1",
            employeeName = "Mia",
            tipTotal = "3.0000",
            cashTip = "2.0000",
            cardTip = "1.0000",
        )
    private val tom =
        mia.copy(
            employeeId = "22222222-2222-2222-2222-222222222222",
            employeeName = "Tom",
            tipTotal = "4.0000",
            cashTip = "4.0000",
            cardTip = "0.0000",
        )

    private val closing =
        ZBonDto.ZBonResponseDto(
            id = 9L,
            zbonNummer = 9L,
            shopInfoId = 1L,
            shopName = "Shop",
            employeeNumber = "ADM",
            startTime = "2026-10-04T08:00:00",
            endTime = "2026-10-04T22:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "60.00",
            openingBalance = "0.00",
            expenses = "-7.00",
            closingBalance = "85.70",
            createdAt = "2026-10-04T22:00:00",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
        zBonApi = FakeZBonApiClient()
        zBonApi.createZBonResult = closing
        zBonApi.openPeriodTipsResult = listOf(mia, tom)
        kassenbuchApi = FakeKassenbuchApiClient()
        cashBookApi = FakeCashBookApiClient()
        kassenbuchApi.getCashCountExpectationResult =
            KassenbuchDto.CashCountExpectationDto(bookBalance = "50.00", unbookedCashTakings = "42.70", expectedCash = "92.70")
        vm = ZBonViewModel(zBonApi, kassenbuchApi, LanguageManager(), cashBookApi)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.open(includeTipPayouts: Boolean = true): ZBonClosingDraft {
        vm.openClosingDialog(includeTipPayouts)
        advanceUntilIdle()
        return assertNotNull(vm.zBonState.value.closingDraft)
    }

    private fun draft(): ZBonClosingDraft = assertNotNull(vm.zBonState.value.closingDraft)

    @Test
    fun `every employee with tips is a row, checked and prefilled with the whole tip in comma format`() =
        runTest(testDispatcher) {
            val draft = open()
            assertEquals(listOf("Mia", "Tom"), draft.tipRows.map { it.employeeName })
            assertTrue(draft.tipRows.all { it.checked }, "checked by default")
            assertEquals(listOf("3,00", "4,00"), draft.tipRows.map { it.amount }, "the payout field's comma prefill")
            assertTrue(draft.tipRows.all { it.check == TipPayoutAmountCheck.VALID })
        }

    @Test
    fun `each row carries its cash and card split for display`() =
        runTest(testDispatcher) {
            val row = open().tipRows.single { it.employeeName == "Mia" }
            assertEquals("3.0000", row.total)
            assertEquals("2.0000", row.cash)
            assertEquals("1.0000", row.card)
        }

    @Test
    fun `the expected cash and the count prefill are the drawer after the checked payouts`() =
        runTest(testDispatcher) {
            val draft = open()
            assertEquals(Decimal.parse("7.00"), draft.tipPayoutTotal)
            assertEquals(Decimal.parse("85.70"), draft.expectedAfterPayouts, "92.70 expected - 7.00 paid out")
            assertEquals("", draft.countText, "the count field starts empty: empty = not counted (FO-33)")
            assertEquals("85,70", draft.countPlaceholder, "the post-payout drawer is only the placeholder")

            vm.setClosingTipChecked(tom.employeeId, false)
            assertEquals(Decimal.parse("89.70"), draft().expectedAfterPayouts, "an unchecked row leaves its cash in the drawer")
            assertEquals("89,70", draft().countPlaceholder)

            vm.updateClosingTipAmount(mia.employeeId, "2,50")
            assertEquals(Decimal.parse("90.20"), draft().expectedAfterPayouts, "a reduced amount pays out only that much")
            assertEquals("90,20", draft().countPlaceholder)
            assertEquals("", draft().countText)
        }

    @Test
    fun `a count the operator typed is kept when the payouts change`() =
        runTest(testDispatcher) {
            open()
            vm.updateClosingCount("80,00")
            vm.setClosingTipChecked(tom.employeeId, false)
            assertEquals("80,00", draft().countText, "toggling a payout never overwrites a counted figure")
        }

    @Test
    fun `an amount above the employee's tip is flagged, not paid, and blocks the closing`() =
        runTest(testDispatcher) {
            open()
            vm.updateClosingTipAmount(mia.employeeId, "3,01")
            val row = draft().tipRows.single { it.employeeId == mia.employeeId }
            assertEquals(TipPayoutAmountCheck.EXCEEDS_REMAINING, row.check)
            assertNull(row.payout, "an over-cap amount pays nothing")
            assertEquals(Decimal.parse("4.00"), draft().tipPayoutTotal, "only Tom's valid payout counts")
            assertFalse(draft().canConfirm)

            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(0, zBonApi.createZBonCallCount, "nothing is sent while a row exceeds its tip")

            // Negative control: the exact tip is fine, and so is an unchecked over-cap row.
            vm.updateClosingTipAmount(mia.employeeId, "3,00")
            assertTrue(draft().canConfirm)
            vm.updateClosingTipAmount(mia.employeeId, "9,99")
            vm.setClosingTipChecked(mia.employeeId, false)
            assertTrue(draft().canConfirm, "an unchecked row is not paid, so its amount cannot block")
        }

    @Test
    fun `malformed amounts block the closing`() =
        runTest(testDispatcher) {
            open()
            listOf("0", "-1", "1,234", "abc", "").forEach { text ->
                vm.updateClosingTipAmount(tom.employeeId, text)
                assertEquals(TipPayoutAmountCheck.INVALID, draft().tipRows.single { it.employeeId == tom.employeeId }.check, "'$text'")
                assertFalse(draft().canConfirm, "'$text'")
            }
        }

    @Test
    fun `F65 - a half-typed amount with a trailing comma pays that amount`() =
        runTest(testDispatcher) {
            open()
            vm.updateClosingTipAmount(tom.employeeId, "3,")
            val row = draft().tipRows.single { it.employeeId == tom.employeeId }
            assertEquals(TipPayoutAmountCheck.VALID, row.check, "\"3,\" is 3,00 on its way to \"3,50\"")
            assertEquals(Decimal.parse("3"), row.payout)
            assertEquals(Decimal.parse("6.00"), draft().tipPayoutTotal, "Mia 3,00 + Tom 3,00")
            assertTrue(draft().canConfirm)

            vm.confirmClosing(opensHistory = false)
            advanceUntilIdle()
            assertEquals(
                listOf("3.00", "3"),
                zBonApi.createdWithTipPayouts.single().map { it.amount },
                "the half-typed amount goes out as a canonical decimal",
            )
        }

    @Test
    fun `F65 - an empty amount on a checked row blocks the closing until it is filled or unticked`() =
        runTest(testDispatcher) {
            open()
            vm.updateClosingTipAmount(tom.employeeId, "")
            val row = draft().tipRows.single { it.employeeId == tom.employeeId }
            assertTrue(row.missingAmount, "the field shows the 'enter an amount or untick' hint")
            assertTrue(row.blocksClosing)
            assertNull(row.payout)
            assertFalse(draft().canConfirm, "an empty field is not a payout of zero")

            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(0, zBonApi.createZBonCallCount, "nothing is sent while a ticked row has no amount")

            vm.updateClosingTipAmount(tom.employeeId, "1")
            assertTrue(draft().canConfirm, "a valid amount unblocks")
            vm.updateClosingTipAmount(tom.employeeId, "")
            vm.setClosingTipChecked(tom.employeeId, false)
            assertTrue(draft().canConfirm, "so does unticking the row")
            assertFalse(draft().tipRows.single { it.employeeId == tom.employeeId }.missingAmount, "an unticked row asks for nothing")
        }

    @Test
    fun `F65 - an explicit zero on a checked row blocks rather than counting as unticked`() =
        runTest(testDispatcher) {
            open()
            listOf("0", "0,00", "0,").forEach { text ->
                vm.updateClosingTipAmount(tom.employeeId, text)
                val row = draft().tipRows.single { it.employeeId == tom.employeeId }
                assertTrue(row.missingAmount, "'$text'")
                assertTrue(row.blocksClosing, "'$text'")
                assertFalse(draft().canConfirm, "'$text'")
            }
            assertEquals(1, draft().payoutRequests().size, "only Mia's row would be sent")
            vm.setClosingTipChecked(tom.employeeId, false)
            assertTrue(draft().canConfirm)
        }

    @Test
    fun `confirming sends the count and every checked payout in one request`() =
        runTest(testDispatcher) {
            open()
            vm.setClosingTipChecked(tom.employeeId, false)
            vm.updateClosingTipAmount(mia.employeeId, "2,50")
            vm.updateClosingCount("90,20")

            var created = false
            vm.confirmClosing(opensHistory = false) { created = true }
            advanceUntilIdle()

            assertEquals(1, zBonApi.createZBonCallCount)
            assertEquals(listOf<String?>("90.20"), zBonApi.createdWithCounts, "canonical dot decimal")
            assertEquals(
                listOf(listOf(ZBonDto.CreateTipPayoutRequestDto(employeeId = mia.employeeId, amount = "2.50"))),
                zBonApi.createdWithTipPayouts,
                "only the checked row, at the edited amount",
            )
            assertEquals(0, kassenbuchApi.cashCountCallCount, "the count travels with the closing, never as its own call")
            assertTrue(created)
            assertNull(vm.zBonState.value.closingDraft, "the dialog closes")
            assertEquals(
                9L,
                vm.zBonState.value.createdZBon
                    ?.id,
            )
        }

    @Test
    fun `defaults confirmed unchanged pay out every tip`() =
        runTest(testDispatcher) {
            open()
            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    listOf(
                        ZBonDto.CreateTipPayoutRequestDto(employeeId = mia.employeeId, amount = "3.00"),
                        ZBonDto.CreateTipPayoutRequestDto(employeeId = tom.employeeId, amount = "4.00"),
                    ),
                ),
                zBonApi.createdWithTipPayouts,
            )
            assertEquals(listOf<String?>(null), zBonApi.createdWithCounts, "an untouched count field is never sent (FO-33)")
        }

    @Test
    fun `without the payout capability no tips are loaded and nothing is paid`() =
        runTest(testDispatcher) {
            val draft = open(includeTipPayouts = false)
            assertEquals(0, zBonApi.openPeriodTipsCallCount, "the section is not even asked for")
            assertTrue(draft.tipRows.isEmpty())
            assertEquals("92,70", draft.countPlaceholder, "the expectation is the plain pre-payout drawer")

            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(listOf(emptyList<ZBonDto.CreateTipPayoutRequestDto>()), zBonApi.createdWithTipPayouts)
        }

    @Test
    fun `a failed tip lookup still lets the day close, without payouts`() =
        runTest(testDispatcher) {
            zBonApi.openPeriodTipsThrows = NetworkApiException(RuntimeException("offline"))
            val draft = open()
            assertTrue(draft.tipRows.isEmpty())
            assertTrue(draft.canConfirm)
            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(1, zBonApi.createZBonCallCount)
            assertEquals(listOf(emptyList<ZBonDto.CreateTipPayoutRequestDto>()), zBonApi.createdWithTipPayouts)
        }

    @Test
    fun `dismissing the dialog closes nothing`() =
        runTest(testDispatcher) {
            open()
            vm.dismissClosingDialog()
            assertNull(vm.zBonState.value.closingDraft)
            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(0, zBonApi.createZBonCallCount)
        }
}
