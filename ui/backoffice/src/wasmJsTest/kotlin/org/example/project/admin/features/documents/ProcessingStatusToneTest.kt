package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.components.documentFlowSteps
import org.example.project.admin.features.documents.presentation.components.processingStatusTone
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.ui.components.beleg.BelegTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessingStatusToneTest {
    @Test
    fun everyStatusHasATone() {
        ProcessingStatus.entries.forEach { processingStatusTone(it) }
    }

    @Test
    fun greyBlueGreenRedFollowTheQueueMeaning() {
        assertEquals(BelegTone.Neutral, processingStatusTone(ProcessingStatus.NONE))
        assertEquals(BelegTone.Info, processingStatusTone(ProcessingStatus.COMPLETED))
        assertEquals(BelegTone.Info, processingStatusTone(ProcessingStatus.IN_PROGRESS))
        assertEquals(BelegTone.Success, processingStatusTone(ProcessingStatus.REVIEWED))
        assertEquals(BelegTone.Danger, processingStatusTone(ProcessingStatus.FAILED))
        assertEquals(BelegTone.Danger, processingStatusTone(ProcessingStatus.UNPROCESSABLE))
    }

    @Test
    fun anInvoiceDocumentsProgressFollowsItsStatusAndLinks() {
        val invoice = DocumentType.INVOICE.name
        assertEquals(1, documentFlowSteps(ProcessingStatus.NONE, false, 0, invoice))
        assertEquals(1, documentFlowSteps(ProcessingStatus.FAILED, false, 0, invoice))
        assertEquals(1, documentFlowSteps(ProcessingStatus.IN_PROGRESS, false, 0, invoice), "in progress without an invoice has not been captured")
        assertEquals(2, documentFlowSteps(ProcessingStatus.COMPLETED, true, 0, invoice))
        assertEquals(2, documentFlowSteps(ProcessingStatus.COMPLETED, true, 3, invoice), "a link before review does not advance the squares")
        assertEquals(3, documentFlowSteps(ProcessingStatus.REVIEWED, true, 0, invoice))
        assertEquals(4, documentFlowSteps(ProcessingStatus.REVIEWED, true, 2, invoice))
    }

    @Test
    fun aDocumentOutsideTheInvoiceFlowHasNoProgress() {
        assertNull(documentFlowSteps(ProcessingStatus.REVIEWED, true, 1, DocumentType.BANK_STATEMENT.name))
        assertNull(documentFlowSteps(ProcessingStatus.NONE, false, 0, null))
    }
}
