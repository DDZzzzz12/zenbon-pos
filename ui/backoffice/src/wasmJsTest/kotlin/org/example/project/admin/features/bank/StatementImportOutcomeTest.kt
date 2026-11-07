package org.example.project.admin.features.bank

import org.example.project.admin.features.bank.presentation.StatementImportOutcome
import org.example.project.admin.features.documents.presentation.DocumentUploadState
import org.example.project.admin.features.documents.presentation.UploadOutcome
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.document.StatementImportNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun uploaded(
    status: ProcessingStatus,
    notices: List<StatementImportNotice> = emptyList(),
    invoice: DocumentDto.InvoiceMetadataDto? = null,
) = DocumentUploadState(
    fileName = "camt.xml",
    documentId = 9,
    uploaded =
        DocumentDto.DocumentResponseDto(
            id = 9,
            filename = "camt.xml",
            fileType = "BANK_STATEMENT",
            fileSize = 2048,
            uploadedAt = "2026-09-24T10:00:00",
            uploadedBy = "A001",
            downloadUrl = "/api/documents/9/file",
            formattedFileSize = "2 KB",
            monthFolder = "2026-09",
            processingStatus = status,
            invoiceMetadata = invoice,
            statementImportNotices = notices,
        ),
)

/**
 * T5-9: the Bankkonten import card only ever said "Beleg angehängt", so a statement the server had
 * refused for not reconciling, and one overlapping an earlier import, both read as a plain success.
 */
class StatementImportOutcomeTest {
    @Test
    fun `a statement whose balances do not reconcile is reported as refused, not attached`() {
        assertEquals(
            StatementImportOutcome.NotReconciled,
            StatementImportOutcome.of(uploaded(ProcessingStatus.FAILED, listOf(StatementImportNotice.BALANCES_DO_NOT_RECONCILE))),
        )
    }

    @Test
    fun `an unreadable statement is told apart from an unreconciled one`() {
        assertEquals(StatementImportOutcome.Unreadable, StatementImportOutcome.of(uploaded(ProcessingStatus.FAILED)))
    }

    @Test
    fun `an overlapping statement says so on the import itself`() {
        assertEquals(
            StatementImportOutcome.Imported(overlapsEarlier = true),
            StatementImportOutcome.of(uploaded(ProcessingStatus.COMPLETED, listOf(StatementImportNotice.OVERLAPS_EARLIER_STATEMENT))),
        )
        assertEquals(
            StatementImportOutcome.Imported(overlapsEarlier = false),
            StatementImportOutcome.of(uploaded(ProcessingStatus.COMPLETED)),
        )
    }

    /**
     * E2E 18.8: an XRechnung picked in "Auszug importieren" came back FAILED — from the e-invoice
     * parser — and the card said the statement could not be read. The server's NOT_A_BANK_STATEMENT
     * notice outranks the status: the file is not a statement, not a broken one.
     */
    @Test
    fun `a file declared as a statement that carries none is not reported as unreadable`() {
        assertEquals(
            StatementImportOutcome.NoStatement,
            StatementImportOutcome.of(uploaded(ProcessingStatus.FAILED, listOf(StatementImportNotice.NOT_A_BANK_STATEMENT))),
        )
        assertEquals(
            StatementImportOutcome.NoStatement,
            StatementImportOutcome.of(uploaded(ProcessingStatus.NONE, listOf(StatementImportNotice.NOT_A_BANK_STATEMENT))),
        )
    }

    @Test
    fun `a file with no statement inside is filed, not imported`() {
        assertEquals(StatementImportOutcome.NoStatement, StatementImportOutcome.of(uploaded(ProcessingStatus.NONE)))
        val invoice = DocumentDto.InvoiceMetadataDto(invoiceNumber = "RE-1", currency = "EUR")
        assertEquals(
            StatementImportOutcome.NoStatement,
            StatementImportOutcome.of(uploaded(ProcessingStatus.COMPLETED, invoice = invoice)),
            "an e-invoice picked here completes too, but it is not a statement",
        )
    }

    @Test
    fun `a duplicate file is reported as such, and nothing is said while an upload is in flight`() {
        assertEquals(
            StatementImportOutcome.Duplicate,
            StatementImportOutcome.of(DocumentUploadState(outcome = UploadOutcome.Duplicate)),
        )
        assertNull(StatementImportOutcome.of(DocumentUploadState(fileName = "camt.xml", isUploading = true)))
        assertNull(StatementImportOutcome.of(DocumentUploadState()))
    }
}
