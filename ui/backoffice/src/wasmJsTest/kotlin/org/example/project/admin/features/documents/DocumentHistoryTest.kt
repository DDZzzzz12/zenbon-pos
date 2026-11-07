package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.DocumentFacts
import org.example.project.admin.features.documents.presentation.DocumentHistoryStep
import org.example.project.admin.features.documents.presentation.documentHistory
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentHistoryTest {
    private fun history(
        status: ProcessingStatus,
        hasInvoice: Boolean,
        links: Int = 0,
    ) = documentHistory(status, hasInvoice, links).associate { it.step to (it.done to it.current) }

    @Test
    fun uploadedOnlyHasCaptureAsTheNextStep() {
        val h = history(ProcessingStatus.NONE, hasInvoice = false)
        assertEquals(true to false, h.getValue(DocumentHistoryStep.UPLOADED))
        assertEquals(false to true, h.getValue(DocumentHistoryStep.CAPTURED))
        assertEquals(false to false, h.getValue(DocumentHistoryStep.REVIEWED))
        assertEquals(false to false, h.getValue(DocumentHistoryStep.LINKED))
    }

    @Test
    fun inProgressWithoutAnInvoiceIsNotCapturedAndNotLinked() {
        val h = history(ProcessingStatus.IN_PROGRESS, hasInvoice = false)
        assertEquals(false to true, h.getValue(DocumentHistoryStep.CAPTURED), "no invoice data exists yet")
        assertEquals(false to false, h.getValue(DocumentHistoryStep.REVIEWED))
        assertEquals(false to false, h.getValue(DocumentHistoryStep.LINKED))
    }

    @Test
    fun aStoredInvoiceWaitsForReview() {
        val h = history(ProcessingStatus.COMPLETED, hasInvoice = true)
        assertEquals(true to false, h.getValue(DocumentHistoryStep.CAPTURED))
        assertEquals(false to true, h.getValue(DocumentHistoryStep.REVIEWED))
    }

    @Test
    fun aReviewedInvoiceWaitsForItsLinkUntilARealOneExists() {
        assertEquals(false to true, history(ProcessingStatus.REVIEWED, true).getValue(DocumentHistoryStep.LINKED))
        assertEquals(true to false, history(ProcessingStatus.REVIEWED, true, links = 1).getValue(DocumentHistoryStep.LINKED))
    }

    @Test
    fun aRealLinkIsShownAsItIsEvenBeforeTheInvoiceIsCaptured() {
        val h = history(ProcessingStatus.IN_PROGRESS, hasInvoice = false, links = 3)
        assertEquals(true to false, h.getValue(DocumentHistoryStep.LINKED), "a real link is a fact, shown as it is")
        assertEquals(false to true, h.getValue(DocumentHistoryStep.CAPTURED))
    }

    @Test
    fun reviewedWithoutAStoredInvoiceIsNotClaimedAsReviewed() {
        assertEquals(false to false, history(ProcessingStatus.REVIEWED, hasInvoice = false).getValue(DocumentHistoryStep.REVIEWED))
    }

    @Test
    fun atMostOneStepIsCurrentAndNoneWhenEverythingIsDone() {
        ProcessingStatus.entries.forEach { status ->
            listOf(true, false).forEach { inv -> assertTrue(documentHistory(status, inv, 0).count { it.current } <= 1, "$status $inv") }
        }
        assertEquals(0, documentHistory(ProcessingStatus.REVIEWED, true, 1).count { it.current })
    }

    @Test
    fun anInvoiceIsLinkedOnlyByAllocationsNeverByDocumentLinks() {
        // The wire's linkCount would say 1 (the file link); only allocations count for an invoice.
        assertEquals(0, DocumentFacts.realLinkCount("INVOICE", allocations = 0, kassenbuch = 1, bankStatements = 1, documents = 1))
        assertEquals(2, DocumentFacts.realLinkCount("INVOICE", allocations = 2, kassenbuch = 0, bankStatements = 0, documents = 0))
    }

    @Test
    fun otherTypesCountTheirNamedLinksAndAnyAllocations() {
        assertEquals(2, DocumentFacts.realLinkCount("BANK_STATEMENT", allocations = 0, kassenbuch = 0, bankStatements = 0, documents = 2))
        assertEquals(1, DocumentFacts.realLinkCount("OUTGOING_INVOICE", allocations = 1, kassenbuch = 0, bankStatements = 0, documents = 0))
        assertEquals(0, DocumentFacts.realLinkCount("CONTRACTS", allocations = 0, kassenbuch = 0, bankStatements = 0, documents = 0))
    }

    @Test
    fun theListItemUsesTheServersHasInvoiceAndAllocationCountNotTheFlattenedFields() {
        fun item(
            hasInvoice: Boolean,
            allocations: Int,
        ) = DocumentDto.DocumentListItemDto(
            id = 1,
            filename = "a.pdf",
            fileType = "INVOICE",
            processingStatus = ProcessingStatus.REVIEWED,
            isActive = true,
            linkCount = 1,
            allocationCount = allocations,
            hasInvoice = hasInvoice,
            amount = "10.00",
            vendorName = "Legacy GmbH",
        )
        // Amount and vendor are present (the legacy fallback fills them), yet there is no invoice row.
        assertEquals(false, DocumentFacts.hasInvoice(item(hasInvoice = false, allocations = 0)))
        assertEquals(true, DocumentFacts.hasInvoice(item(hasInvoice = true, allocations = 0)))
        assertEquals(0, DocumentFacts.realLinkCount(item(true, 0)), "linkCount = 1 is not a payment")
        assertEquals(2, DocumentFacts.realLinkCount(item(true, 2)))
    }
}
