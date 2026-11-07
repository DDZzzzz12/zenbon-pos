/**
 * PDF Generation Service Interface
 *
 * Defines the contract for PDF document generation operations.
 * Handles conversion of business entities (Bills) to PDF format.
 *
 * Service Layer Responsibility:
 * - Business logic for PDF generation
 * - Return DTOs (not entities)
 * - Control transaction boundaries
 * - Coordinate between repositories
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 6.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.service

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.features.document.domain.models.Document
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.printer.domain.service.ReceiptPdfRenderer
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceLine
import org.example.project.features.salesinvoice.domain.models.SalesInvoiceVatBreakdown
import org.example.project.features.salesinvoice.domain.service.SalesInvoicePdfData
import java.util.UUID

/**
 * Service interface for PDF generation operations.
 *
 * Converts business entities to PDF documents with proper formatting,
 * storage organization, and metadata tracking.
 */
interface PdfGenerationService : ReceiptPdfRenderer {
    /**
     * Generates a formal invoice (Rechnung) PDF in letter format.
     *
     * Creates a professional A4 invoice document with:
     * - Shop/company header with contact information
     * - Customer address section
     * - Invoice details (number, date, customer number)
     * - Itemized product/service list with prices
     * - VAT breakdown and totals
     * - Bank account information for payment
     * - Legal footer text (including Kleinunternehmerregelung if applicable)
     *
     * Note: The caller is responsible for deleting the temp file after sending response.
     *
     * @param bill Bill response DTO with all bill data
     * @param tseQrCodeData Optional TSE QR code data for DSFinV-K compliance
     * @param request Generation request containing bill ID, customer info, and preferences
     * @param employeeId Employee number of the user generating the invoice
     * @return Temporary File containing the generated PDF
     */

    /**
     * Renders an outgoing invoice from its own snapshot and archives it as an `OUTGOING_INVOICE`
     * document.
     *
     * Called once, at issue. Everything afterwards reads the stored file — a template or logo change
     * must never alter what an already-issued invoice looks like.
     */
    suspend fun generateAndStoreSalesInvoicePdf(
        data: SalesInvoicePdfData,
        employeeId: UUID,
    ): Document

    /**
     * Validates an issued-shape invoice against EN 16931 before a ZUGFeRD e-invoice is written.
     *
     * Maps the invoice to the syntax-neutral e-invoice model and runs the structural (VAT/total
     * consistency) and required-field checks. Returns `null` when the invoice is a valid EN 16931
     * document, or the first problem message otherwise (e.g. a missing buyer address).
     *
     * Lives here — not in `pos-server`'s issue flow — because the `:accounting-einvoice` mapper/validators are
     * only visible to `accounting-document`. Must run inside the caller's transaction: it reads the
     * invoice's lazy `precedingSalesInvoice` reference.
     */
    suspend fun validateSalesInvoiceEInvoice(
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
    ): String?

    /**
     * Renders and archives a hybrid ZUGFeRD PDF/A-3 for an opted-in debtor.
     *
     * Maps the invoice to the e-invoice model, serializes the EN 16931 CII, embeds it in the same
     * visible letter the plain path draws, and stores the result exactly like
     * [generateAndStoreSalesInvoicePdf] (same `OUTGOING_INVOICE` type, same hashing/month-folder).
     * The caller is expected to have run [validateSalesInvoiceEInvoice] first. Must run inside the
     * caller's transaction.
     */
    suspend fun generateAndStoreSalesInvoiceZugferdPdf(
        data: SalesInvoicePdfData,
        invoice: SalesInvoice,
        lines: List<SalesInvoiceLine>,
        vatBreakdown: List<SalesInvoiceVatBreakdown>,
        employeeId: UUID,
    ): Document

    /**
     * Generates an Eigenbeleg (self-issued receipt) PDF for a Kassenbuch entry.
     *
     * Creates a simple A4 PDF document and persists it to disk with a database record.
     * Used when no external invoice is attached to a Kassenbuch entry.
     *
     * When [correctsBelegNr] is non-blank the document is rendered as a
     * Storno-Eigenbeleg: the title changes to "Storno-Eigenbeleg Nr. $belegNr"
     * and two additional detail rows are added ("Storniert:" and "Grund:").
     *
     * @param belegNr Sequential booking number
     * @param date Formatted German date string
     * @param entryType German label for the entry type
     * @param amount Formatted amount with currency
     * @param description Belegtext/purpose
     * @param employeeId Employee UUID who triggered the generation
     * @param correctsBelegNr Beleg-Nr. of the original entry being reversed (null for ordinary Eigenbeleg)
     * @param correctsDate Booking date of the original entry
     * @param reason Mandatory correction reason rendered on the Storno-Eigenbeleg
     * @return DocumentResponseDto for the persisted PDF
     */
    suspend fun generateEigenbelegPdf(
        belegNr: String,
        date: String,
        entryType: String,
        amount: String,
        description: String,
        employeeId: java.util.UUID,
        correctsBelegNr: String? = null,
        correctsDate: String? = null,
        reason: String? = null,
    ): DocumentDto.DocumentResponseDto

    /**
     * Generates a Z-Bon report PDF from the Z-Bon response data.
     *
     * Creates a full A4 PDF replicating the ESC/POS Z-Bon layout and persists
     * it to disk with a database record. Linked to the Z-Bon's Kassenbuch entry.
     *
     * @param zBon Complete Z-Bon response DTO
     * @param employeeId Employee UUID who triggered the generation
     * @return DocumentResponseDto for the persisted PDF
     */
    suspend fun generateZBonReportPdf(
        zBon: ZBonDto.ZBonResponseDto,
        employeeId: UUID,
        kassenbuchHeader: KassenbuchHeader? = null,
    ): DocumentDto.DocumentResponseDto
}
