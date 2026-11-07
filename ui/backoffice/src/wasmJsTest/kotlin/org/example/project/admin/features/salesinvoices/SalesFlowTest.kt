package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.SalesFlow
import org.example.project.admin.features.salesinvoices.presentation.SalesFlowStage
import org.example.project.admin.features.salesinvoices.presentation.SalesHistoryStep
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceBucket
import org.example.project.admin.features.salesinvoices.presentation.SalesNextStep
import org.example.project.admin.features.salesinvoices.presentation.SalesRowStatus
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SalesFlowTest {
    private fun row(
        status: String = "ISSUED",
        type: String = "380",
        open: String? = null,
    ) = SalesInvoiceDto.ListItemDto(
        id = 1,
        invoiceNumber = "AR-1",
        status = status,
        invoiceTypeCode = type,
        issueDate = "2026-10-14T00:00",
        buyerName = "Hotel Sonnenhof",
        currency = "EUR",
        taxInclusiveTotal = "100.0000",
        openAmount = open,
        hasDocument = true,
    )

    @Test
    fun theSubFiltersOfAusgestelltBelongToThatStage() {
        assertEquals(SalesFlowStage.DRAFT, SalesFlow.stageOf(SalesInvoiceBucket.DRAFTS))
        assertEquals(SalesFlowStage.OPEN, SalesFlow.stageOf(SalesInvoiceBucket.OPEN))
        SalesFlow.ISSUED_BUCKETS.forEach { assertEquals(SalesFlowStage.ISSUED, SalesFlow.stageOf(it), it.name) }
    }

    @Test
    fun everyBucketBelongsToExactlyOneStageAndEachStageOpensItsOwnBucket() {
        SalesInvoiceBucket.entries.forEach { SalesFlow.stageOf(it) }
        SalesFlowStage.entries.forEach { assertEquals(it, SalesFlow.stageOf(it.bucket)) }
    }

    @Test
    fun draftsComeBeforeOpenInvoicesInTheNextStep() {
        val counts = mapOf(SalesInvoiceBucket.DRAFTS to 2L, SalesInvoiceBucket.OPEN to 6L, SalesInvoiceBucket.ISSUED to 11L)
        assertEquals(SalesNextStep(drafts = 2, open = 6), SalesFlow.nextStep(counts))
    }

    @Test
    fun noNextStepWhenNothingWaitsOrCountsAreUnknown() {
        assertNull(SalesFlow.nextStep(emptyMap()))
        assertNull(SalesFlow.nextStep(mapOf(SalesInvoiceBucket.DRAFTS to 0L, SalesInvoiceBucket.OPEN to 0L)))
    }

    @Test
    fun documentStateDecidesCancelledCreditNoteAndDraft() {
        assertEquals(SalesRowStatus.CANCELLED, SalesFlow.statusOf(row(status = "CANCELLED"), SalesInvoiceBucket.CANCELLED))
        assertEquals(SalesRowStatus.CREDIT_NOTE, SalesFlow.statusOf(row(type = "381"), SalesInvoiceBucket.CREDIT_NOTES))
        assertEquals(SalesRowStatus.DRAFT, SalesFlow.statusOf(row(status = "DRAFT"), SalesInvoiceBucket.DRAFTS))
    }

    @Test
    fun anIssuedInvoiceIsOpenUntilItsOpenAmountIsZero() {
        assertEquals(SalesRowStatus.OPEN, SalesFlow.statusOf(row(open = "2140.0000"), SalesInvoiceBucket.ISSUED))
        assertEquals(SalesRowStatus.PAID, SalesFlow.statusOf(row(open = "0.0000"), SalesInvoiceBucket.ISSUED))
    }

    @Test
    fun theOverdueBucketMarksItsOpenRowsOverdue() {
        assertEquals(SalesRowStatus.OVERDUE, SalesFlow.statusOf(row(open = "860.0000"), SalesInvoiceBucket.OVERDUE))
    }

    @Test
    fun anUntrackedReceivableIsNeverClaimedAsPaid() {
        assertEquals(SalesRowStatus.ISSUED, SalesFlow.statusOf(row(open = null), SalesInvoiceBucket.ISSUED))
        assertEquals(SalesRowStatus.ISSUED, SalesFlow.statusOf(row(open = "not a number"), SalesInvoiceBucket.ISSUED))
    }

    @Test
    fun theHistoryMarksEachStepReached() {
        val draft = SalesFlow.history("DRAFT", documentId = null, openAmount = null).toMap()
        assertEquals(
            mapOf(
                SalesHistoryStep.DRAFTED to true,
                SalesHistoryStep.ISSUED to false,
                SalesHistoryStep.ARCHIVED to false,
                SalesHistoryStep.PAID to false,
            ),
            draft,
        )

        val open = SalesFlow.history("ISSUED", documentId = 7, openAmount = "10.00").toMap()
        assertTrue(open.getValue(SalesHistoryStep.ISSUED) && open.getValue(SalesHistoryStep.ARCHIVED))
        assertFalse(open.getValue(SalesHistoryStep.PAID))

        val paid = SalesFlow.history("ISSUED", documentId = 7, openAmount = "0.00").toMap()
        assertTrue(paid.getValue(SalesHistoryStep.PAID))
    }

    @Test
    fun aDraftWithAZeroOpenAmountIsNotPaid() {
        assertFalse(SalesFlow.history("DRAFT", documentId = null, openAmount = "0.00").toMap().getValue(SalesHistoryStep.PAID))
    }

    @Test
    fun hasOpenAmountNeedsAPositiveParseableAmount() {
        assertTrue(SalesFlow.hasOpenAmount("0.01"))
        assertFalse(SalesFlow.hasOpenAmount("0.00"))
        assertFalse(SalesFlow.hasOpenAmount("-5.00"))
        assertFalse(SalesFlow.hasOpenAmount(null))
        assertFalse(SalesFlow.hasOpenAmount("x"))
    }
}
