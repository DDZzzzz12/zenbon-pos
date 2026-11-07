package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
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
 * F59 in the "Z-Bon erstellen" dialog: the Kassenminus shortfall and the top-up from another cash
 * book (option A), and "über Bank/Lohn ausgezahlt" (option C).
 *
 * The drawer expects 5.00 — a float of 5.00, no cash takings; Mia (3.00) and Tom (4.00) are checked
 * for 7.00 in cash — 2.00 short. The prefill is the float-preserving 7.00 (payouts − takings). The
 * shop has a Tresor holding 10.00, a Hauptkasse holding 1.00 (too little), an inactive Altkasse holding
 * 50.00 and the fiscal Ladenkasse itself — only the Tresor may cover the shortfall.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonClosingTopUpViewModelTest {
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
            cashTip = "0.0000",
            cardTip = "3.0000",
        )
    private val tom = mia.copy(employeeId = "22222222-2222-2222-2222-222222222222", employeeName = "Tom", tipTotal = "4.0000", cardTip = "4.0000")

    private fun book(
        id: Long,
        name: String,
        balance: String,
        isFiscal: Boolean = false,
        isActive: Boolean = true,
    ) = CashBookDto.ResponseDto(
        id = id,
        name = name,
        isFiscal = isFiscal,
        datevAccount = "1000",
        closeInterval = CashBookCloseInterval.MANUAL,
        isActive = isActive,
        sortOrder = id.toInt(),
        currentBalance = balance,
        currency = "EUR",
    )

    private val ladenkasse = book(1L, "Ladenkasse", "5.00", isFiscal = true)
    private val tresor = book(2L, "Tresor", "10.00")
    private val hauptkasse = book(3L, "Hauptkasse", "1.00")
    private val altkasse = book(4L, "Altkasse", "50.00", isActive = false)

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
            totalRevenue = "20.00",
            openingBalance = "5.00",
            expenses = "-7.00",
            closingBalance = "0.00",
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
        kassenbuchApi.getCashCountExpectationResult =
            KassenbuchDto.CashCountExpectationDto(bookBalance = "5.00", unbookedCashTakings = "0.00", expectedCash = "5.00")
        cashBookApi = FakeCashBookApiClient()
        cashBookApi.listBooksResult = listOf(ladenkasse, tresor, hauptkasse, altkasse)
        vm = ZBonViewModel(zBonApi, kassenbuchApi, LanguageManager(), cashBookApi)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.open(): ZBonClosingDraft {
        vm.openClosingDialog(includeTipPayouts = true)
        advanceUntilIdle()
        return assertNotNull(vm.zBonState.value.closingDraft)
    }

    private fun draft(): ZBonClosingDraft = assertNotNull(vm.zBonState.value.closingDraft)

    @Test
    fun `cash payouts above the drawer show the shortfall and prefill the float-preserving top-up`() =
        runTest(testDispatcher) {
            val draft = open()
            assertEquals(Decimal.parse("2.00"), draft.shortfall, "7.00 paid out of a 5.00 drawer")
            assertEquals(listOf(tresor.id), draft.eligibleTopUpSources.map { it.cashBookId }, "not the Ladenkasse, the inactive book or one holding 1.00")
            assertEquals(tresor.id, draft.topUpSourceId, "the only eligible book is preselected")
            assertEquals("7,00", draft.topUpText, "payouts − takings: the takings are 0, so the top-up pays all of it")
            assertEquals(Decimal.parse("5.00"), draft.expectedAfterPayouts, "the drawer ends at its 5.00 float")
            assertTrue(draft.canConfirm)
        }

    @Test
    fun `with cash takings in the drawer the prefill is only what the takings cannot pay`() =
        runTest(testDispatcher) {
            kassenbuchApi.getCashCountExpectationResult =
                KassenbuchDto.CashCountExpectationDto(bookBalance = "2.00", unbookedCashTakings = "3.00", expectedCash = "5.00")
            val draft = open()
            assertEquals(Decimal.parse("2.00"), draft.shortfall, "7.00 out of 2.00 float + 3.00 takings")
            assertEquals("4,00", draft.topUpText, "7.00 payouts − 3.00 takings")
            assertEquals(Decimal.parse("2.00"), draft.expectedAfterPayouts, "2 + 3 + 4 − 7: the float, untouched")

            // The operator may still lower it to the bare shortfall — the drawer then ends at 0.
            vm.updateClosingTopUpAmount("2,00")
            assertTrue(draft().canConfirm)
            assertEquals(Decimal.ZERO, draft().expectedAfterPayouts)
        }

    @Test
    fun `confirming sends the top-up with the payouts in the one closing request`() =
        runTest(testDispatcher) {
            open()
            vm.confirmClosing()
            advanceUntilIdle()

            assertEquals(1, zBonApi.createZBonCallCount)
            assertEquals(
                listOf<ZBonDto.ClosingTopUpRequestDto?>(ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor.id, amount = "7.00")),
                zBonApi.createdWithTopUps,
            )
            assertEquals(2, zBonApi.createdWithTipPayouts.single().size)
        }

    @Test
    fun `a top-up below the shortfall, or no book that can cover it, blocks the closing`() =
        runTest(testDispatcher) {
            open()
            vm.updateClosingTopUpAmount("1,99")
            assertTrue(draft().topUpTooLow)
            assertFalse(draft().canConfirm)
            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(0, zBonApi.createZBonCallCount, "nothing is sent while the drawer cannot pay")

            vm.updateClosingTopUpAmount("10,01")
            assertNull(draft().topUpSource, "the Tresor holds only 10.00")
            assertFalse(draft().canConfirm)

            // Negative control: an amount above the shortfall that the Tresor holds is fine.
            vm.updateClosingTopUpAmount("4,00")
            assertTrue(draft().canConfirm)
            assertEquals(Decimal.parse("2.00"), draft().expectedAfterPayouts, "5 + 4 − 7: part of the float went to the staff")
        }

    @Test
    fun `without an eligible book the closing cannot be confirmed`() =
        runTest(testDispatcher) {
            cashBookApi.listBooksResult = listOf(ladenkasse, hauptkasse)
            val draft = open()
            assertEquals(Decimal.parse("2.00"), draft.shortfall)
            assertNull(draft.topUpSource)
            assertFalse(draft.canConfirm)
            assertNull(draft.topUpRequest())
        }

    @Test
    fun `paying a tip via bank or payroll takes it out of the drawer figures - no shortfall, no top-up`() =
        runTest(testDispatcher) {
            open()
            vm.setClosingTipMethod(tom.employeeId, TipPayoutMethod.BANK_PAYROLL)

            assertNull(draft().shortfall, "only Mia's 3.00 leaves the drawer now")
            assertEquals(Decimal.parse("3.00"), draft().tipPayoutTotal, "the cash total excludes the bank row")
            assertEquals(Decimal.parse("2.00"), draft().expectedAfterPayouts)
            assertEquals("", draft().topUpText)

            vm.confirmClosing()
            advanceUntilIdle()
            assertEquals(listOf<ZBonDto.ClosingTopUpRequestDto?>(null), zBonApi.createdWithTopUps, "no top-up asked for")
            assertEquals(
                listOf(
                    ZBonDto.CreateTipPayoutRequestDto(employeeId = mia.employeeId, amount = "3.00", method = TipPayoutMethod.CASH),
                    ZBonDto.CreateTipPayoutRequestDto(employeeId = tom.employeeId, amount = "4.00", method = TipPayoutMethod.BANK_PAYROLL),
                ),
                zBonApi.createdWithTipPayouts.single(),
                "the bank row is still settled — it clears Tom's open amount",
            )
        }

    @Test
    fun `a failed cash-book lookup leaves the shortfall blocking rather than guessing a source`() =
        runTest(testDispatcher) {
            cashBookApi.listBooksThrows = NetworkApiException(RuntimeException("offline"))
            val draft = open()
            assertEquals(Decimal.parse("2.00"), draft.shortfall)
            assertFalse(draft.canConfirm)
        }
}
