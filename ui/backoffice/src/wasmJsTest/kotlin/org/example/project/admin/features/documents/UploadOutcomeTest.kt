package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.UploadOutcome
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun invoice() =
    DocumentDto.InvoiceMetadataDto(
        vendorName = "Metro AG",
        invoiceNumber = "MET-2026-0417",
        grossAmount = "2380.00",
        currency = "EUR",
    )

private fun uploaded(
    status: ProcessingStatus,
    invoice: DocumentDto.InvoiceMetadataDto? = null,
) = DocumentDto.DocumentResponseDto(
    id = 7,
    filename = "Rechnung.xml",
    fileType = "INVOICE",
    fileSize = 4633,
    uploadedAt = "2026-09-02T11:00:00",
    uploadedBy = "A001",
    downloadUrl = "/api/documents/7/file",
    formattedFileSize = "4.5 KB",
    monthFolder = "2026-09",
    processingStatus = status,
    invoiceMetadata = invoice,
)

/**
 * How an upload's server response becomes the one sentence an operator reads.
 *
 * The mapping matters more than it looks: three of the four outcomes are successes, and the screen
 * styles them by case. Getting `NONE` wrong would paint every scanned receipt as a failure.
 */
class UploadOutcomeTest {
    @Test
    fun `a parsed e-invoice reports the invoice it read`() {
        val outcome = UploadOutcome.of(uploaded(ProcessingStatus.COMPLETED, invoice()))

        val extracted = assertIs<UploadOutcome.Extracted>(outcome)
        assertEquals(7, extracted.documentId)
        assertEquals("MET-2026-0417", extracted.invoice.invoiceNumber)
    }

    /** The ordinary case: paper. It must not be an error, or the panel becomes noise to dismiss. */
    @Test
    fun `a scan with no structured payload is not an error`() {
        assertIs<UploadOutcome.NoEInvoice>(UploadOutcome.of(uploaded(ProcessingStatus.NONE)))
    }

    /**
     * ZUGFeRD MINIMUM / BASIC WL. Not extracted by design — they are booking aids, and presenting
     * their figures as an invoice would show numbers the profile does not guarantee.
     */
    @Test
    fun `a non-conformant profile is reported as incomplete, not as unreadable`() {
        val outcome = UploadOutcome.of(uploaded(ProcessingStatus.UNPROCESSABLE))

        val notExtractable = assertIs<UploadOutcome.NotExtractable>(outcome)
        assertTrue(!notExtractable.unreadable, "a booking-aid profile is not a broken file")
    }

    @Test
    fun `a payload the parser could not read is reported as unreadable`() {
        val outcome = UploadOutcome.of(uploaded(ProcessingStatus.FAILED))

        assertTrue(assertIs<UploadOutcome.NotExtractable>(outcome).unreadable)
    }

    /**
     * COMPLETED with nothing attached should be impossible — the server writes it only once the
     * invoice is stored. It degrades to the case that asks the operator to look, rather than
     * claiming a successful read while showing nothing read.
     */
    @Test
    fun `COMPLETED without an invoice degrades instead of claiming a read`() {
        assertIs<UploadOutcome.NoEInvoice>(UploadOutcome.of(uploaded(ProcessingStatus.COMPLETED, invoice = null)))
    }

    /** The workflow states an operator sets later are none of an upload's business. */
    @Test
    fun `a human workflow state maps to the least-claiming outcome`() {
        assertIs<UploadOutcome.NoEInvoice>(UploadOutcome.of(uploaded(ProcessingStatus.REVIEWED)))
        assertIs<UploadOutcome.NoEInvoice>(UploadOutcome.of(uploaded(ProcessingStatus.IN_PROGRESS)))
    }
}
