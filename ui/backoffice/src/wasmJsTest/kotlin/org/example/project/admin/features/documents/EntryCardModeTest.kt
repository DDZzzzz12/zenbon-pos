package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.DocumentFacts
import org.example.project.admin.features.documents.presentation.DocumentHistoryStep
import org.example.project.admin.features.documents.presentation.EntryCardMode
import org.example.project.admin.features.documents.presentation.documentHistory
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class EntryCardModeTest {
    private fun mode(
        status: ProcessingStatus,
        hasInvoice: Boolean,
    ) = DocumentFacts.entryCardMode(status, hasInvoice)

    @Test
    fun noStoredInvoiceIsCapturedWhateverTheStatus() {
        listOf(ProcessingStatus.NONE, ProcessingStatus.IN_PROGRESS, ProcessingStatus.UNPROCESSABLE, ProcessingStatus.FAILED).forEach {
            assertEquals(EntryCardMode.CAPTURE, mode(it, false), it.name)
        }
    }

    @Test
    fun aStoredInvoiceNotYetReviewedIsToBeChecked() {
        listOf(ProcessingStatus.NONE, ProcessingStatus.IN_PROGRESS, ProcessingStatus.COMPLETED).forEach {
            assertEquals(EntryCardMode.VERIFY, mode(it, true), it.name)
        }
    }

    @Test
    fun aReviewedInvoiceNeedsNoEntryCard() {
        assertEquals(EntryCardMode.NONE, mode(ProcessingStatus.REVIEWED, true))
    }

    @Test
    fun theCardAndTheVerlaufAgreeOnWhetherTheInvoiceIsCaptured() {
        // Erfasst is ticked exactly when the card does not ask for capture.
        ProcessingStatus.entries.forEach { status ->
            listOf(true, false).forEach { inv ->
                val erfasst = documentHistory(status, inv, 0).first { it.step == DocumentHistoryStep.CAPTURED }.done
                assertEquals(erfasst, mode(status, inv) != EntryCardMode.CAPTURE, "$status $inv")
            }
        }
    }
}
