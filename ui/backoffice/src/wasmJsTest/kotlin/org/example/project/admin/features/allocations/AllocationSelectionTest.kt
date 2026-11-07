package org.example.project.admin.features.allocations

import org.example.project.admin.features.allocations.presentation.AllocationSelection
import org.example.project.admin.features.allocations.presentation.AllocationsState
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllocationSelectionTest {
    private fun invoice(
        id: Long,
        open: String,
    ) = AllocationDto.OpenInvoiceDto(invoiceId = id, openAmount = open)

    private fun state(vararg drafts: Pair<String, String>) =
        AllocationsState(
            openInvoices = listOf(invoice(1, "1284.60"), invoice(2, "412.30")),
            amountDrafts = drafts.toMap(),
        )

    @Test
    fun onlyCandidatesWithAValidAmountAreSelected() {
        val picked = AllocationSelection.selected("INVOICE", state("INVOICE:1" to "1284,60", "INVOICE:2" to ""), restOpen = "1284.60")
        assertEquals(listOf(1L), picked.map { it.targetId })
        assertEquals("1284.60", picked.single().amount)
    }

    @Test
    fun anAmountAboveTheInvoicesOwnResidualIsNotSelected() {
        val picked = AllocationSelection.selected("INVOICE", state("INVOICE:2" to "500,00"), restOpen = "1284.60")
        assertTrue(picked.isEmpty(), "412,30 is all invoice 2 still owes")
    }

    @Test
    fun theTotalMustNotExceedThePaymentsOpenAmount() {
        val s = state("INVOICE:1" to "1284,60", "INVOICE:2" to "412,30")
        val picked = AllocationSelection.selected("INVOICE", s, restOpen = "1500.00")
        assertEquals(2, picked.size)
        assertEquals("1696.90", AllocationSelection.total(picked).toPlainString())
        assertFalse(AllocationSelection.canAssign(picked, "1500.00"))
        assertTrue(AllocationSelection.canAssign(picked, "1696.90"))
    }

    @Test
    fun nothingSelectedCannotBeAssigned() {
        assertFalse(AllocationSelection.canAssign(emptyList(), "10.00"))
    }

    @Test
    fun theRemainderIsTheOpenAmountMinusTheTotal() {
        assertEquals("0.00", AllocationSelection.remaining("1284.60", Decimal.parse("1284.60"))?.toPlainString())
        assertNull(AllocationSelection.remaining("not a number", Decimal.ZERO))
    }

    @Test
    fun tickingFillsTheCandidatesOpenAmountCappedByWhatIsLeft() {
        assertEquals("412.30", AllocationSelection.prefill("412.30", "1284.60", Decimal.ZERO))
        assertEquals("284.60", AllocationSelection.prefill("1284.60", "1284.60", Decimal.parse("1000.00")))
        assertEquals("178.00", AllocationSelection.prefill("178.0000", "1284.6000", Decimal.ZERO), "two places, not the wire's four")
        assertNull(AllocationSelection.prefill("10.00", "100.00", Decimal.parse("100.00")), "nothing left to give")
    }

    @Test
    fun otherKindsHaveNoPickedCandidates() {
        assertTrue(AllocationSelection.candidates("FEE", state()).isEmpty())
    }

    @Test
    fun anAmountAboveTheInvoicesOwnOpenAmountIsFlaggedOnThatField() {
        val s = state("INVOICE:2" to "500,00", "INVOICE:1" to "100,00")
        assertEquals(setOf("INVOICE:2"), AllocationSelection.overCandidate("INVOICE", s))
        assertTrue(AllocationSelection.exceeds("412,31", "412.30"))
        assertFalse(AllocationSelection.exceeds("412,30", "412.30"))
        assertFalse(AllocationSelection.exceeds("", "412.30"))
    }

    @Test
    fun theSummaryExplainsATotalAboveThePaymentsOpenAmount() {
        val s = state("INVOICE:1" to "1284,60", "INVOICE:2" to "412,30")
        assertTrue(AllocationSelection.overPayment("INVOICE", s, restOpen = "1500.00"))
        assertFalse(AllocationSelection.overPayment("INVOICE", s, restOpen = "1696.90"))
    }

    @Test
    fun anAmountAboveItsInvoiceIsNotAlsoBlamedOnThePayment() {
        val s = state("INVOICE:2" to "500,00")
        assertFalse(AllocationSelection.overPayment("INVOICE", s, restOpen = "450.00"), "that field already carries its own message")
    }
}
