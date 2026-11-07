package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * admin16 FAIL-2: a session listing only its Storno RE-27 (−19,90) and the sale RE-44 (17,90)
 * showed "Umsatz −2,00 €" because the cancelled original RE-8 was filtered out of the list while its
 * Storno was summed. The list now holds every receipt and the revenue counts active bills only.
 */
class BillActiveRevenueTest {
    private fun bill(
        id: Long,
        paid: String,
        deletedBillId: String? = null,
        deletingReason: String? = null,
        relations: BillDto.BillRelationsDto = BillDto.BillRelationsDto(),
    ) = BillDto.BillResponseDto(
        billId = id,
        employeeId = "emp-1",
        employeeNumber = "K001",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 4786L,
        tableNumber = "1",
        billNumber = "RE-$id",
        billAmount = paid,
        paidAmount = paid,
        tips = "0.00",
        startTime = "2026-10-01T20:45:00",
        endTime = "2026-10-01T21:44:00",
        deletedBillId = deletedBillId,
        deletingReason = deletingReason,
        orderItems = emptyList(),
        payments = emptyList(),
        relations = relations,
    )

    private fun link(id: Long) = BillDto.BillLinkDto(id, "RE-$id")

    /** The live session 4786: a Zahlartwechsel of RE-2, then an ordinary Storno of its Neubuchung RE-8. */
    private val session =
        listOf(
            bill(2, "19.90", deletingReason = "RE-7 : Zahlartwechsel", relations = BillDto.BillRelationsDto(isCancelled = true)),
            bill(
                7,
                "-19.90",
                deletedBillId = "2",
                relations =
                    BillDto.BillRelationsDto(
                        kind = BillKind.STORNO,
                        reversesBill = link(2),
                        stornoReason = StornoReason.ZAHLARTWECHSEL,
                    ),
            ),
            bill(
                8,
                "19.90",
                deletingReason = "RE-27 : falsch",
                relations = BillDto.BillRelationsDto(kind = BillKind.REBOOKING, isCancelled = true, rebooksBill = link(2)),
            ),
            bill(
                27,
                "-19.90",
                deletedBillId = "8",
                relations = BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(8)),
            ),
            bill(44, "17.90"),
        )

    @Test
    fun session_revenue_counts_active_bills_only() {
        assertEquals(0, session.activeBillsRevenue().compareTo(Decimal.parse("17.90")))
    }

    @Test
    fun active_revenue_equals_the_signed_sum_of_every_receipt() {
        // Every Storno pair cancels out, so both rules agree on a complete list.
        val signed = session.fold(Decimal.ZERO) { acc, bill -> acc + Decimal.parse(bill.paidAmount) }
        assertEquals(0, signed.compareTo(session.activeBillsRevenue()))
    }

    @Test
    fun storno_and_cancelled_original_are_not_active_and_the_neubuchung_is() {
        val neubuchung = bill(9, "19.90", relations = BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = link(2)))
        assertTrue(neubuchung.isActiveBill())
        assertTrue(bill(44, "17.90").isActiveBill())
        assertFalse(session.first { it.billId == 2L }.isActiveBill())
        assertFalse(session.first { it.billId == 27L }.isActiveBill())
    }

    @Test
    fun raw_storno_columns_decide_without_relations() {
        // An older server sends no relations: the raw columns alone still classify the pair.
        assertFalse(bill(1, "10.00", deletingReason = "x").isActiveBill())
        assertFalse(bill(3, "-10.00", deletedBillId = "1").isActiveBill())
    }

    @Test
    fun a_receivable_clearing_is_active_but_not_revenue() {
        // The sale RE-44 went "auf Rechnung"; RE-45 collected the invoice later. Turnover arose once,
        // on RE-44 — the Z-Bon's Umsatz leaves RE-45 out, and so does a session's revenue.
        val clearing =
            bill(
                45,
                "17.90",
                relations = BillDto.BillRelationsDto(kind = BillKind.RECEIVABLE_CLEARING, clearsBill = link(44)),
            )
        assertTrue(clearing.isActiveBill())
        assertFalse(clearing.countsAsRevenue())
        assertTrue(bill(44, "17.90").countsAsRevenue())
        assertEquals(0, (session + clearing).activeBillsRevenue().compareTo(Decimal.parse("17.90")))
    }

    @Test
    fun a_training_receipt_is_listed_but_never_revenue() {
        // emu20 NOTE-t: the Z-Bon receipt list now carries Schulungsbelege; its revenue must not.
        val training = bill(46, "7.40").copy(isTraining = true)
        assertFalse(training.countsAsRevenue())
        assertEquals(0, (session + training).activeBillsRevenue().compareTo(Decimal.parse("17.90")))
    }
}
