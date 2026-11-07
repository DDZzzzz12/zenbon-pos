package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZBonClosingDraftReadinessTest {
    private val row =
        ClosingTipPayoutRow(employeeId = "e1", employeeName = "Anna", total = "5.00", cash = "5.00", card = "0.00", amount = "5,00")

    private val noSigner = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.NOT_DESIGNATED)

    @Test
    fun `without a signer the request carries no count or top-up, only the bank payouts`() {
        val draft = ZBonClosingDraft(expectedCash = "100.00", tipRows = listOf(row), countText = "99,00", countEdited = true, readiness = noSigner)
        val request = draft.toRequest()
        assertNull(request.countedAmount)
        assertNull(request.topUp)
        assertEquals(
            listOf(ZBonDto.CreateTipPayoutRequestDto(employeeId = "e1", amount = "5.00", method = TipPayoutMethod.BANK_PAYROLL)),
            request.tipPayouts,
            "a bank/payroll payout books no Kassenbuch entry and needs no signer",
        )
        assertTrue(draft.canConfirm)
    }

    @Test
    fun `with the TSE disabled movements are allowed, the count and cash payouts are sent`() {
        val disabled = ZBonDto.ClosingReadinessDto(tseDisabled = true, dailyTransferConfigured = true)
        val draft = ZBonClosingDraft(expectedCash = "100.00", tipRows = listOf(row), countText = "99,00", countEdited = true, readiness = disabled)
        assertTrue(draft.movementsAllowed)
        assertFalse(draft.blockedBySigner)
        assertNotNull(draft.toRequest().countedAmount)
        assertEquals(listOf(TipPayoutMethod.CASH), draft.payoutRequests().map { it.method })
    }

    @Test
    fun `without a signer every row is forced to bank or payroll, whatever method it carried`() {
        val cashRow = row.copy(method = TipPayoutMethod.CASH)
        val draft = ZBonClosingDraft(tipRows = listOf(cashRow), readiness = noSigner)
        assertEquals(listOf(TipPayoutMethod.BANK_PAYROLL), draft.effectiveTipRows.map { it.method })
        assertEquals(listOf(TipPayoutMethod.BANK_PAYROLL), draft.payoutRequests().map { it.method })
        // Negative control: with a signer the choice is the operator's.
        val withSigner = draft.copy(readiness = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1"))
        assertEquals(listOf(TipPayoutMethod.CASH), withSigner.payoutRequests().map { it.method })
    }

    @Test
    fun `forced bank rows move no cash - no shortfall, no top-up, expectation untouched`() {
        val big = row.copy(total = "500.00", amount = "500,00")
        val draft = ZBonClosingDraft(expectedCash = "100.00", tipRows = listOf(big), readiness = noSigner)
        assertEquals(Decimal.ZERO, draft.tipPayoutTotal)
        assertNull(draft.shortfall)
        assertNull(draft.topUpRequest())
        assertEquals(Decimal.parse("100.00"), draft.expectedAfterPayouts)
        assertFalse(draft.topUpBlocksClosing)
        // Negative control: the same row in cash with a signer does overdraw the drawer.
        val cash = draft.copy(readiness = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1"))
        assertNotNull(cash.shortfall)
        assertFalse(cash.canConfirm)
    }

    @Test
    fun `without a signer an invalid count or top-up is ignored but a bad payout amount still blocks`() {
        val ok = ZBonClosingDraft(expectedCash = "100.00", tipRows = listOf(row), countText = "abc", countEdited = true, topUpText = "x", readiness = noSigner)
        assertTrue(ok.canConfirm, "the hidden count field cannot block")
        val bad = ok.copy(tipRows = listOf(row.copy(amount = "9,00")))
        assertFalse(bad.canConfirm, "9.00 exceeds the 5.00 tip")
        val empty = ok.copy(tipRows = listOf(row.copy(amount = "")))
        assertFalse(empty.canConfirm)
        // An unchecked row is simply not sent.
        val unchecked = ok.copy(tipRows = listOf(row.copy(checked = false)))
        assertTrue(unchecked.canConfirm)
        assertTrue(unchecked.toRequest().tipPayouts.isEmpty())
    }

    @Test
    fun `a blocked closing stays blocked whatever the rows say`() {
        val draft =
            ZBonClosingDraft(
                tipRows = listOf(row),
                readiness = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.INACTIVE, dailyTransferConfigured = true),
            )
        assertFalse(draft.canConfirm)
    }

    @Test
    fun `a missing signer plus a daily transfer blocks the closing`() {
        val draft =
            ZBonClosingDraft(
                readiness = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.NOT_DESIGNATED, dailyTransferConfigured = true),
            )
        assertFalse(draft.canConfirm)
    }

    @Test
    fun `with a signer the request is unchanged`() {
        val draft =
            ZBonClosingDraft(
                expectedCash = "100.00",
                countText = "99,00",
                countEdited = true,
                readiness = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1"),
            )
        assertEquals("99.00", draft.toRequest().countedAmount)
    }

    @Test
    fun `unknown readiness does not restrict the till`() {
        val draft = ZBonClosingDraft(expectedCash = "100.00", countText = "99,00", countEdited = true)
        assertEquals("99.00", draft.toRequest().countedAmount)
    }

    // FO-33: only a count the operator typed is sent; the expectation is a placeholder, not a value.
    @Test
    fun `an untouched count field sends no count`() {
        val draft = ZBonClosingDraft(expectedCash = "100.00")
        assertEquals("", draft.countText)
        assertEquals("100,00", draft.countPlaceholder)
        assertNull(draft.toRequest().countedAmount)
    }

    @Test
    fun `a count value without the edited flag is not sent`() {
        val draft = ZBonClosingDraft(expectedCash = "100.00", countText = "100,00", countEdited = false)
        assertNull(draft.toRequest().countedAmount, "a prefilled-looking value must never book a difference")
    }

    @Test
    fun `an edited count is sent canonical and an emptied one is not`() {
        assertEquals("99.50", ZBonClosingDraft(expectedCash = "100.00", countText = "99,50", countEdited = true).toRequest().countedAmount)
        assertNull(ZBonClosingDraft(expectedCash = "100.00", countText = "", countEdited = true).toRequest().countedAmount)
    }
}
