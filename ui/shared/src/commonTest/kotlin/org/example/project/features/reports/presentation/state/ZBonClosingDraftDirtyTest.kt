package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZBonClosingDraftDirtyTest {
    private val row =
        ClosingTipPayoutRow(employeeId = "e1", employeeName = "Anna", total = "5.00", cash = "5.00", card = "0.00", amount = "5,00")

    @Test
    fun `an opened and auto-prefilled draft is clean`() {
        assertFalse(ZBonClosingDraft().isDirty)
        assertFalse(ZBonClosingDraft(expectedCash = "100.00", countText = "100,00", tipRows = listOf(row)).isDirty)
    }

    @Test
    fun `typing a count or a top-up is dirty`() {
        assertTrue(ZBonClosingDraft(countText = "99", countEdited = true).isDirty)
        assertTrue(ZBonClosingDraft(topUpText = "5", topUpEdited = true).isDirty)
    }

    @Test
    fun `a tip row that deviates from its prefilled default is dirty`() {
        assertTrue(ZBonClosingDraft(tipRows = listOf(row.copy(checked = false))).isDirty)
        assertTrue(ZBonClosingDraft(tipRows = listOf(row.copy(amount = "3,00"))).isDirty)
        assertTrue(ZBonClosingDraft(tipRows = listOf(row.copy(method = TipPayoutMethod.BANK_PAYROLL))).isDirty)
    }
}
