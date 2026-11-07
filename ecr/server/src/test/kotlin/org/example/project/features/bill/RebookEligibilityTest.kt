package org.example.project.features.bill

import org.example.project.features.bill.domain.RebookEligibility
import org.example.project.features.bill.domain.RebookFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RebookEligibilityTest {
    private val plain =
        RebookFacts(
            isStandingSale = true,
            isVirtualTable = false,
            hasVoucherItem = false,
            clearsInvoice = false,
        )

    @Test
    fun `a plain standing sale is rebookable`() = assertNull(RebookEligibility.refusal(plain))

    @Test
    fun `a Storno or cancelled sale is not a standing sale`() =
        assertEquals(RebookEligibility.Refusal.NOT_A_STANDING_SALE, RebookEligibility.refusal(plain.copy(isStandingSale = false)))

    @Test
    fun `a gift-voucher sale and a receivable clearing are unsupported`() {
        listOf(
            plain.copy(isVirtualTable = true),
            plain.copy(hasVoucherItem = true),
            plain.copy(clearsInvoice = true),
        ).forEach { assertEquals(RebookEligibility.Refusal.UNSUPPORTED, RebookEligibility.refusal(it), "$it") }
    }
}
