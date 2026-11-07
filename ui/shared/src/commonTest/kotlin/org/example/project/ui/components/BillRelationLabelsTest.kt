package org.example.project.ui.components

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which chips a list row carries and which links a detail panel offers. */
class BillRelationLabelsTest {
    private val re4 = BillDto.BillLinkDto(4, "RE-4")
    private val re5 = BillDto.BillLinkDto(5, "RE-5")
    private val re7 = BillDto.BillLinkDto(7, "RE-7")

    @Test
    fun anOrdinarySaleCarriesNoChipsAndNoLinks() {
        assertTrue(BillDto.BillRelationsDto().listLabels().isEmpty())
        assertTrue(BillDto.BillRelationsDto().detailLinks().isEmpty())
    }

    @Test
    fun aStornoNamesTheBillItReverses() {
        val relations = BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = re4)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationStornoOf, re4, isWarning = true)), relations.listLabels())
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationStornoOf, re4, isWarning = true)), relations.detailLinks())
    }

    @Test
    fun aCancelledOriginalShowsItsStatusAndLinksItsStorno() {
        val relations = BillDto.BillRelationsDto(isCancelled = true, reversedBy = re7)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationCancelled, isWarning = true)), relations.listLabels())
        assertEquals(
            listOf(BillRelationLabel(BillReceiptKeys.relationReversedByLink, re7, isWarning = true)),
            relations.detailLinks(),
        )
    }

    @Test
    fun aZahlartwechselStornoReadsAsAPaymentChangeNotAsARefund() {
        val relations = BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = re4, stornoReason = StornoReason.ZAHLARTWECHSEL)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationStornoRebookOf, re4)), relations.listLabels())
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationStornoRebookOf, re4)), relations.detailLinks())
    }

    @Test
    fun aRebookingNamesItsOriginal() {
        val relations = BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = re4)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationRebookOf, re4)), relations.listLabels())
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationRebookOf, re4)), relations.detailLinks())
    }

    @Test
    fun anOriginalReplacedByARebookingReadsAsAPaymentChangeAndLinksBothItsStornoAndTheNewBill() {
        val relations = BillDto.BillRelationsDto(isCancelled = true, reversedBy = re5, rebookedBy = re7)
        assertEquals(
            listOf(BillRelationLabel(BillReceiptKeys.relationCancelledRebook)),
            relations.listLabels(),
            "a Zahlartwechsel refunded nothing, so the chip is not the warning-coloured \"Storniert\"",
        )
        assertEquals(
            listOf(
                BillRelationLabel(BillReceiptKeys.relationReversedByLink, re5, isWarning = true),
                BillRelationLabel(BillReceiptKeys.relationRebookedByLink, re7),
            ),
            relations.detailLinks(),
        )
    }

    @Test
    fun aSaleThatSettlesHandedBackItemsNamesItsOriginalAsANeubuchung() {
        val relations = BillDto.BillRelationsDto(rebilledFromBills = listOf(re4))
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationRebookOf, re4)), relations.listLabels())
        assertEquals("RE-4", relations.listLabels().single().argument)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationRebookOf, re4)), relations.detailLinks())
    }

    @Test
    fun aPartialReBillOfTwoOriginalsReadsAsOneChipAndLinksEachOriginal() {
        val relations = BillDto.BillRelationsDto(rebilledFromBills = listOf(re4, re7), rebillIsPartial = true)
        val chip = relations.listLabels().single()
        assertEquals(BillReceiptKeys.relationRebookOfPartial, chip.key, "a bill with new items too reads \"(teilweise)\"")
        assertEquals("RE-4, RE-7", chip.argument, "both originals in one chip, comma-joined")
        assertEquals(re4, chip.target, "a tap on the chip opens the first original")
        assertEquals(
            listOf(
                BillRelationLabel(BillReceiptKeys.relationRebookOfPartial, re4),
                BillRelationLabel(BillReceiptKeys.relationRebookOfPartial, re7),
            ),
            relations.detailLinks(),
        )
    }

    @Test
    fun aStatusLabelHasNoArgument() {
        assertNull(BillRelationLabel(BillReceiptKeys.relationCancelled).argument)
    }

    @Test
    fun aClearingNamesTheInvoicedBillWhenThereIsOne() {
        val withBill = BillDto.BillRelationsDto(kind = BillKind.RECEIVABLE_CLEARING, clearsBill = re4)
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.relationClearingOf, re4)), withBill.listLabels())
        assertTrue(BillDto.BillRelationsDto(kind = BillKind.RECEIVABLE_CLEARING).listLabels().isEmpty())
    }

    @Test
    fun theTrailGoesBackAlongFollowedLinksAndCollapsesCycles() {
        fun bill(id: Long) =
            BillDto.BillResponseDto(
                billId = id,
                employeeId = "e",
                employeeNumber = "07",
                tipRecipient = "EMPLOYEE",
                tableSessionId = 1,
                tableNumber = "1",
                billNumber = "RE-$id",
                billAmount = "0",
                paidAmount = "0",
                tips = "0",
                startTime = "",
                endTime = "",
                orderItems = emptyList(),
                payments = emptyList(),
            )
        val trail = BillDetailTrail().follow(bill(4), 5).follow(bill(5), 6)
        assertEquals(re5, trail.backTo)

        val (previous, rest) = trail.back()
        assertEquals(5L, previous?.billId)
        assertEquals(BillDto.BillLinkDto(4, "RE-4"), rest.backTo)

        // RE-4 → RE-5 → RE-6 → back to RE-4 by link: nothing left to go back to.
        assertNull(trail.follow(bill(6), 4).backTo)
        assertNull(BillDetailTrail().back().first)
    }

    private fun bill(
        isTraining: Boolean = false,
        relations: BillDto.BillRelationsDto = BillDto.BillRelationsDto(),
    ) = BillDto.BillResponseDto(
        billId = 1,
        employeeId = "emp-1",
        employeeNumber = "K001",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 1L,
        tableNumber = "1",
        billNumber = "RE-1",
        billAmount = "10.00",
        paidAmount = "10.00",
        tips = "0.00",
        startTime = "2026-10-01T20:00:00",
        endTime = "2026-10-01T20:10:00",
        orderItems = emptyList(),
        payments = emptyList(),
        isTraining = isTraining,
        relations = relations,
    )

    // admin20 NOTE-B: the detail panel showed a Schulungsbeleg exactly like a sale.
    @Test
    fun aTrainingReceiptLeadsTheDetailPanelWithATrainingBadge() {
        assertTrue(bill().detailStatusLabels().isEmpty(), "an ordinary sale carries no status chip")
        assertEquals(listOf(BillRelationLabel(BillReceiptKeys.trainingBadge)), bill(isTraining = true).detailStatusLabels())
        assertEquals(
            listOf(
                BillRelationLabel(BillReceiptKeys.trainingBadge),
                BillRelationLabel(BillReceiptKeys.relationCancelledRebook),
            ),
            bill(isTraining = true, relations = BillDto.BillRelationsDto(isCancelled = true, rebookedBy = re5)).detailStatusLabels(),
        )
        assertEquals(
            listOf(BillRelationLabel(BillReceiptKeys.relationCancelled, isWarning = true)),
            bill(relations = BillDto.BillRelationsDto(isCancelled = true, reversedBy = re7)).detailStatusLabels(),
        )
    }
}
