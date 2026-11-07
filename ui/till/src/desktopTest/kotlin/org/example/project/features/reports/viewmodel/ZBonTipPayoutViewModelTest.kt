package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The till's "Trinkgeld auszahlen" flow on a Z-Bon's detail (F51): the dialog state, the client
 * half of the amount guard (CLAUDE.md rule 8), the request, and how the shown closing updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonTipPayoutViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var api: FakeZBonApiClient
    private lateinit var vm: ZBonViewModel

    private val anna =
        ZBonDto.EmployeeTipPayoutStatusDto(
            employeeId = "11111111-1111-1111-1111-111111111111",
            employeeNumber = "W1",
            employeeName = "Anna",
            tipTotal = "5.0000",
            paidOut = "0.0000",
            remaining = "5.0000",
        )
    private val bert = anna.copy(employeeId = "22222222-2222-2222-2222-222222222222", employeeName = "Bert", remaining = "4.0000")

    private val closing =
        ZBonDto.ZBonResponseDto(
            id = 7L,
            zbonNummer = 7L,
            shopInfoId = 1L,
            shopName = "Shop",
            employeeNumber = "ADM",
            startTime = "2026-10-03T08:00:00",
            endTime = "2026-10-03T22:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "60.00",
            openingBalance = "0.00",
            expenses = "0.00",
            closingBalance = "69.00",
            createdAt = "2026-10-03T22:00:00",
            tipPayouts = listOf(anna, bert),
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
        api = FakeZBonApiClient()
        api.createZBonResult = closing
        vm = ZBonViewModel(api, FakeKassenbuchApiClient(), LanguageManager(), FakeCashBookApiClient())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Shows [closing] as the history's selected Z-Bon, as creating it does. */
    private suspend fun kotlinx.coroutines.test.TestScope.showClosing() {
        vm.createZBonWithCount(countedAmount = null, opensHistory = true)
        advanceUntilIdle()
        assertEquals(
            7L,
            vm.zBonState.value.selectedZBon
                ?.id,
        )
    }

    @Test
    fun `opening the dialog pre-fills the whole open amount`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna)

            val draft = assertNotNull(vm.zBonState.value.tipPayoutDraft)
            assertEquals("5,00", draft.amount, "pre-filled in the till's comma money-input convention (F54 #1)")
            assertEquals(anna.employeeId, draft.employeeId)
            assertEquals(TipPayoutAmountCheck.VALID, draft.check)
        }

    @Test
    fun `the prefill carries no dot, symbol or grouping and round-trips to the canonical amount`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna.copy(remaining = "1234.5000"))

            val draft = assertNotNull(vm.zBonState.value.tipPayoutDraft)
            assertEquals("1234,50", draft.amount)
            assertEquals("1234.50", draft.amount.toCanonicalMoneyOrNull())
            assertEquals(TipPayoutAmountCheck.VALID, draft.check)
        }

    @Test
    fun `a sub-cent remainder prefills rounded down, so the prefill never exceeds what is open`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna.copy(remaining = "3.0050"))

            val draft = assertNotNull(vm.zBonState.value.tipPayoutDraft)
            assertEquals("3,00", draft.amount)
            assertEquals(TipPayoutAmountCheck.VALID, draft.check)
        }

    @Test
    fun `nothing opens for an employee with nothing left`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna.copy(paidOut = "5.00", remaining = "0.0000"))
            assertNull(vm.zBonState.value.tipPayoutDraft)
        }

    @Test
    fun `the amount guard classifies typed input`() {
        assertEquals(TipPayoutAmountCheck.VALID, TipPayoutAmountCheck.of("2,50", "5.0000"), "comma decimal")
        assertEquals(TipPayoutAmountCheck.VALID, TipPayoutAmountCheck.of("5", "5.0000"), "the exact remainder")
        assertEquals(TipPayoutAmountCheck.EXCEEDS_REMAINING, TipPayoutAmountCheck.of("5.01", "5.0000"))
        listOf("", "0", "-1", "1.234", "abc", "1.000,00").forEach {
            assertEquals(TipPayoutAmountCheck.INVALID, TipPayoutAmountCheck.of(it, "5.0000"), "'$it'")
        }
    }

    @Test
    fun `an over-payout is refused locally and sends nothing`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna)
            vm.updateTipPayoutAmount("5,01")
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            assertTrue(api.payOutTipCalls.isEmpty(), "the client guard must stop the request")
            assertEquals(
                TipPayoutAmountCheck.EXCEEDS_REMAINING,
                vm.zBonState.value.tipPayoutDraft
                    ?.check,
            )
        }

    @Test
    fun `a confirmed partial payout sends the canonical amount and updates the shown closing`() =
        runTest(testDispatcher) {
            showClosing()
            api.payOutTipResult =
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = 99L,
                    belegNr = "2026-10-0042",
                    status = anna.copy(paidOut = "2.5000", remaining = "2.5000"),
                )
            vm.openTipPayout(7L, anna)
            vm.updateTipPayoutAmount("2,50")
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            val (zBonId, request) = api.payOutTipCalls.single()
            assertEquals(7L, zBonId)
            assertEquals(anna.employeeId, request.employeeId)
            assertEquals("2.50", request.amount, "a dot-decimal wire string, never the typed comma")

            val state = vm.zBonState.value
            assertNull(state.tipPayoutDraft, "the dialog closes on success")
            val shown = state.selectedZBon!!.tipPayouts
            assertEquals("2.5000", shown.single { it.employeeId == anna.employeeId }.remaining)
            assertEquals("4.0000", shown.single { it.employeeId == bert.employeeId }.remaining, "other employees untouched")
            assertNotNull(state.successMessage)
        }

    @Test
    fun `a server refusal keeps the dialog open, re-enabled, with a typed error`() =
        runTest(testDispatcher) {
            showClosing()
            api.payOutTipThrows =
                PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING, 409, "Tip payout exceeds the remaining", null)
            vm.openTipPayout(7L, anna)
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            val state = vm.zBonState.value
            val draft = assertNotNull(state.tipPayoutDraft, "the operator can correct the amount and retry")
            assertEquals(false, draft.isSubmitting)
            val error = assertIs<UiError.FromServer>(state.error)
            assertEquals(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING, error.code)
            assertEquals(
                "5.0000",
                state.selectedZBon!!
                    .tipPayouts
                    .first()
                    .remaining,
                "nothing changed on a refusal",
            )
        }

    @Test
    fun `a double confirm sends one request`() =
        runTest(testDispatcher) {
            showClosing()
            api.payOutTipResult = ZBonDto.TipPayoutResponseDto(1L, "B", anna.copy(paidOut = "5.0000", remaining = "0.0000"))
            vm.openTipPayout(7L, anna)
            vm.confirmTipPayout("EUR")
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            assertEquals(1, api.payOutTipCalls.size)
        }

    @Test
    fun `dismissing closes the dialog without booking`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna)
            vm.dismissTipPayout()
            advanceUntilIdle()

            assertNull(vm.zBonState.value.tipPayoutDraft)
            assertTrue(api.payOutTipCalls.isEmpty())
        }

    @Test
    fun `a payout from the created-closing dialog updates that closing`() =
        runTest(testDispatcher) {
            vm.createZBonWithCount(countedAmount = null, opensHistory = false)
            advanceUntilIdle()
            api.payOutTipResult = ZBonDto.TipPayoutResponseDto(1L, "B", bert.copy(paidOut = "4.0000", remaining = "0.0000"))
            vm.openTipPayout(7L, bert)
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            assertEquals(
                "0.0000",
                vm.zBonState.value.createdZBon!!
                    .tipPayouts
                    .single { it.employeeId == bert.employeeId }
                    .remaining,
            )
        }

    // --- Storno of a booked payout -----------------------------------------------------------

    private val booked = ZBonDto.TipPayoutRecordDto(41L, "2026-10-0041", "2.5000", "2026-10-04T08:00:00", reversed = false)
    private val annaPartlyPaid = anna.copy(paidOut = "2.5000", remaining = "2.5000", payouts = listOf(booked))

    @Test
    fun `a reversed payout opens no storno dialog`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipReversal(7L, annaPartlyPaid, booked.copy(reversed = true))
            assertNull(vm.zBonState.value.tipReversalDraft)
        }

    @Test
    fun `a storno without a reason sends nothing`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipReversal(7L, annaPartlyPaid, booked)
            vm.updateTipReversalReason("   ")
            vm.confirmTipReversal("EUR")
            advanceUntilIdle()

            assertTrue(api.reverseTipPayoutCalls.isEmpty())
            assertEquals(
                false,
                vm.zBonState.value.tipReversalDraft
                    ?.canConfirm,
            )
        }

    @Test
    fun `a confirmed storno sends the payout entry and reason and shows the payout reversed`() =
        runTest(testDispatcher) {
            showClosing()
            api.reverseTipPayoutResult =
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = 42L,
                    belegNr = "2026-10-0042",
                    status = annaPartlyPaid.copy(paidOut = "0.0000", remaining = "5.0000", payouts = listOf(booked.copy(reversed = true))),
                )
            vm.openTipReversal(7L, annaPartlyPaid, booked)
            vm.updateTipReversalReason("falscher Mitarbeiter")
            vm.confirmTipReversal("EUR")
            vm.confirmTipReversal("EUR")
            advanceUntilIdle()

            val (zBonId, headerId, request) = api.reverseTipPayoutCalls.single()
            assertEquals(7L, zBonId)
            assertEquals(41L, headerId, "the PAYOUT's Kassenbuch entry is stornoed")
            assertEquals("falscher Mitarbeiter", request.reason)

            val state = vm.zBonState.value
            assertNull(state.tipReversalDraft)
            val shown = state.selectedZBon!!.tipPayouts.single { it.employeeId == anna.employeeId }
            assertEquals("5.0000", shown.remaining, "the stornoed amount is open again")
            assertTrue(shown.payouts.single().reversed, "Stornieren is hidden once reversed")
            assertNotNull(state.successMessage)
        }

    @Test
    fun `an already-reversed refusal keeps the dialog with a typed error`() =
        runTest(testDispatcher) {
            showClosing()
            api.reverseTipPayoutThrows = PosApiException(PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED, 409, "twice", null)
            vm.openTipReversal(7L, annaPartlyPaid, booked)
            vm.updateTipReversalReason("Grund")
            vm.confirmTipReversal("EUR")
            advanceUntilIdle()

            val state = vm.zBonState.value
            assertEquals(false, assertNotNull(state.tipReversalDraft).isSubmitting)
            assertEquals(PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED, assertIs<UiError.FromServer>(state.error).code)
        }

    // --- F59: paid via bank / payroll ----------------------------------------------------------

    @Test
    fun `a payout marked bank-payroll sends that method and updates the open amount`() =
        runTest(testDispatcher) {
            showClosing()
            api.payOutTipResult =
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = null,
                    belegNr = null,
                    status = anna.copy(paidOut = "5.0000", remaining = "0.0000"),
                    payoutId = 12L,
                    method = TipPayoutMethod.BANK_PAYROLL,
                )
            vm.openTipPayout(7L, anna)
            vm.setTipPayoutMethod(TipPayoutMethod.BANK_PAYROLL)
            vm.confirmTipPayout("EUR")
            advanceUntilIdle()

            assertEquals(
                ZBonDto.CreateTipPayoutRequestDto(employeeId = anna.employeeId, amount = "5.00", method = TipPayoutMethod.BANK_PAYROLL),
                api.payOutTipCalls.single().second,
            )
            assertEquals(
                "0.0000",
                vm.zBonState.value.selectedZBon!!
                    .tipPayouts
                    .first()
                    .remaining,
            )
        }

    @Test
    fun `the method defaults to cash and cannot change while the request is in flight`() =
        runTest(testDispatcher) {
            showClosing()
            vm.openTipPayout(7L, anna)
            assertEquals(
                TipPayoutMethod.CASH,
                vm.zBonState.value.tipPayoutDraft!!
                    .method,
            )
            api.payOutTipResult = ZBonDto.TipPayoutResponseDto(1L, "B", anna.copy(paidOut = "5.0000", remaining = "0.0000"))
            vm.confirmTipPayout("EUR")
            vm.setTipPayoutMethod(TipPayoutMethod.BANK_PAYROLL)
            advanceUntilIdle()
            assertEquals(
                TipPayoutMethod.CASH,
                api.payOutTipCalls
                    .single()
                    .second.method,
                "the in-flight request keeps its method",
            )
        }

    @Test
    fun `stornoing a bank-payroll settlement goes through the settlement route by its payout id`() =
        runTest(testDispatcher) {
            showClosing()
            val settlement =
                ZBonDto.TipPayoutRecordDto(
                    kassenbuchHeaderId = null,
                    belegNr = null,
                    amount = "5.0000",
                    createdAt = "2026-10-04T08:00:00",
                    reversed = false,
                    payoutId = 12L,
                    method = TipPayoutMethod.BANK_PAYROLL,
                )
            api.reverseTipSettlementResult =
                ZBonDto.TipPayoutResponseDto(null, null, anna.copy(paidOut = "0.0000", remaining = "5.0000"), payoutId = 13L)
            vm.openTipReversal(7L, anna.copy(paidOut = "5.0000", remaining = "0.0000", payouts = listOf(settlement)), settlement)
            vm.updateTipReversalReason("doch bar gezahlt")
            vm.confirmTipReversal("EUR")
            advanceUntilIdle()

            assertEquals(listOf(Triple(7L, 12L, ZBonDto.ReverseTipPayoutRequestDto("doch bar gezahlt"))), api.reverseTipSettlementCalls)
            assertTrue(api.reverseTipPayoutCalls.isEmpty(), "never the cash route, which is keyed by a Kassenbuch entry it does not have")
        }

    /** Negative control: a cash payout is still stornoed through its Kassenbuch entry. */
    @Test
    fun `a cash payout storno still goes through the Kassenbuch-entry route`() =
        runTest(testDispatcher) {
            showClosing()
            api.reverseTipPayoutResult = ZBonDto.TipPayoutResponseDto(42L, "S", anna)
            vm.openTipReversal(7L, annaPartlyPaid, booked)
            vm.updateTipReversalReason("falscher Betrag")
            vm.confirmTipReversal("EUR")
            advanceUntilIdle()

            assertEquals(41L, api.reverseTipPayoutCalls.single().second)
            assertTrue(api.reverseTipSettlementCalls.isEmpty())
        }
}
