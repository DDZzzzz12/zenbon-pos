package org.example.project.admin.features.documents

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.components.processingStatusLabelKey
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The status mapping used to live as a `private @Composable` inside DocumentInfoCard, returning a
 * String — untestable, and no test covered it. The work queue's status column is the second call
 * site, so it was extracted to a pure key mapper instead of duplicated. This pins the whole enum.
 */
class ProcessingStatusLabelTest {
    @Test
    fun everyProcessingStatusHasALabelKey() {
        assertEquals(
            listOf(
                AdminStringKeys.DocumentProcessingStatusNone,
                AdminStringKeys.DocumentProcessingStatusPending,
                AdminStringKeys.DocumentProcessingStatusProcessing,
                AdminStringKeys.DocumentProcessingStatusCompleted,
                AdminStringKeys.DocumentProcessingStatusFailed,
                AdminStringKeys.DocumentProcessingStatusUnprocessable,
                AdminStringKeys.DocumentProcessingStatusInProgress,
                AdminStringKeys.DocumentProcessingStatusReviewed,
            ),
            ProcessingStatus.entries.map { processingStatusLabelKey(it) },
        )
    }

    @Test
    fun completedBankStatementIsNotLabelledAsAnEInvoice() {
        assertEquals(
            AdminStringKeys.DocumentProcessingStatusCompletedStatement,
            processingStatusLabelKey(ProcessingStatus.COMPLETED, "BANK_STATEMENT"),
        )
        assertEquals(
            AdminStringKeys.DocumentProcessingStatusCompleted,
            processingStatusLabelKey(ProcessingStatus.COMPLETED, "INVOICE"),
        )
        assertEquals(
            AdminStringKeys.DocumentProcessingStatusFailed,
            processingStatusLabelKey(ProcessingStatus.FAILED, "BANK_STATEMENT"),
        )
    }
}
