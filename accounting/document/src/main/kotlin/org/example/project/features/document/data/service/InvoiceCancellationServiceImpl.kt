package org.example.project.features.document.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.domain.repository.AllocationRepository
import org.example.project.features.document.data.repository.InvoiceStorno
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.mapper.toDto
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.document.domain.service.InvoiceCancellationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import java.math.BigDecimal

/**
 * See [InvoiceCancellationService]. The incoming-invoice mirror of the outgoing side's
 * `SalesInvoiceServiceImpl.cancel`, which writes a `381` Gutschrift with negated totals and sets both
 * residuals to zero; this does the same with the columns the incoming model already has — the pair is
 * described in [InvoiceStorno].
 *
 * **Refused, not "handled", when the invoice is allocated or marked paid.** Money already went out
 * against it; cancelling it would leave the payment consumed by an invoice that no longer counts, and
 * the residual recompute on the next allocation change would resurrect it. The Zuordnung has to be
 * undone first (*Rückgängig*), which is an audited act of its own.
 */
class InvoiceCancellationServiceImpl(
    private val repository: DocumentRepository,
    private val allocationRepository: AllocationRepository,
) : InvoiceCancellationService {
    override suspend fun cancel(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
        cancelledBy: String?,
    ): DocumentDto.InvoiceDto =
        ExceptionConverter.catchingThrows(
            context = "cancelIncomingInvoice(invoiceId=$invoiceId, cancelledBy=$cancelledBy)",
            employeeId = cancelledBy,
            operation = "CANCEL_INCOMING_INVOICE",
            entityType = "INVOICE",
            auditEntity = { correction -> auditEntityOf(invoiceId, correction) },
            // A refusal names the invoice too, not the "FAILED" placeholder.
            failedEntityId = invoiceId.toString(),
        ) {
            val reason = validatedReason(request)
            dbQuery { writeCorrection(invoiceId, reason).toDto() }
        }

    private fun writeCorrection(
        invoiceId: Long,
        reason: String,
    ): Invoice {
        val original = repository.getInvoice(invoiceId) ?: throw notFound(invoiceId)
        requireCancellable(original)

        val correction =
            repository.createInvoice(
                sourceDocumentId = original.sourceDocument?.id?.value,
                extractionSource = InvoiceStorno.EXTRACTION_SOURCE,
                invoiceNumber = null,
                issueDate = currentTimeStamp(),
                currency = original.currency,
                invoiceTypeCode = InvoiceStorno.TYPE_CODE,
                precedingInvoiceReference = original.invoiceNumber,
                sellerName = original.sellerName,
                sellerVatId = original.sellerVatId,
                sellerTaxNumber = original.sellerTaxNumber,
                sellerStreet = original.sellerStreet,
                sellerZip = original.sellerZip,
                sellerCity = original.sellerCity,
                sellerCountry = original.sellerCountry,
                lineNetTotal = original.lineNetTotal?.negate(),
                taxExclusiveTotal = original.taxExclusiveTotal?.negate(),
                taxTotal = original.taxTotal?.negate(),
                taxInclusiveTotal = original.taxInclusiveTotal?.negate(),
                amountDue = original.amountDue?.negate(),
            )
        // Same creditor, so the supplier's spend nets to zero across the pair.
        correction.supplier = original.supplier
        // Written by us, now — there is nothing for a human to check, so it lands in *Fertig*.
        correction.reviewedAt = currentTimeStamp()
        repository.replaceVatBreakdown(correction.id.value, negatedVatRows(original))
        repository.upsertInvoiceBooking(invoiceId = correction.id.value, bookingText = reason)

        // Both halves of the pair carry a zero residual: neither is an open item anywhere, and the
        // negative correction does not land in *Überzahlt* either.
        correction.openAmount = BigDecimal.ZERO
        original.openAmount = BigDecimal.ZERO
        // A cancelled original has nothing left to check and the client cannot confirm a frozen
        // invoice, so it must not stay in *Zu prüfen* for ever. Same stamp "mark reviewed" writes;
        // an earlier confirmation is kept (the first one is the one an auditor asks about).
        if (original.reviewedAt == null) original.reviewedAt = currentTimeStamp()
        original.updatedAt = currentTimeStamp()
        return correction
    }

    /** Fail fast, in the order an operator would want to hear about it. */
    private fun requireCancellable(original: Invoice) {
        val id = original.id.value
        if (InvoiceStorno.isCorrection(original)) {
            throw notCancellable("Invoice $id is itself a correction (Storno) and cannot be cancelled")
        }
        if (InvoiceStorno.correctionOf(original) != null) {
            throw notCancellable("Invoice $id is already cancelled")
        }
        val documentId =
            original.sourceDocument?.id?.value
                ?: throw notCancellable("Invoice $id has no Beleg; a correction is recorded against its Beleg")
        requireUnambiguousOnDocument(original, documentId)
        if (allocationRepository.allocatedAmountsForInvoice(id).isNotEmpty()) {
            throw notCancellable("Invoice $id is allocated to a payment; undo the Zuordnung before cancelling it")
        }
        if (original.booking?.paidAt != null) {
            throw notCancellable("Invoice $id is marked paid; clear the payment date before cancelling it")
        }
    }

    /**
     * The link is (Beleg, invoice number); two invoices sharing both would make the correction
     * ambiguous, so the second one is refused rather than guessed at.
     */
    private fun requireUnambiguousOnDocument(
        original: Invoice,
        documentId: Long,
    ) {
        val number = original.invoiceNumber
        val twins =
            Invoice
                .find {
                    (InvoiceTable.sourceDocumentId eq documentId) and
                        (InvoiceTable.extractionSource neq InvoiceStorno.EXTRACTION_SOURCE) and
                        (if (number == null) InvoiceTable.invoiceNumber.isNull() else InvoiceTable.invoiceNumber eq number)
                }.count()
        if (twins > 1) {
            throw notCancellable(
                "Invoice ${original.id.value} shares its Beleg and invoice number with another invoice; " +
                    "give it its own invoice number before cancelling it",
            )
        }
    }

    private fun negatedVatRows(original: Invoice): List<DocumentRepository.VatBreakdownRow> =
        original.vatBreakdown.sortedBy { it.id.value }.map { row ->
            DocumentRepository.VatBreakdownRow(
                vatCategoryCode = row.vatCategoryCode,
                vatRate = row.vatRate,
                taxableAmount = row.taxableAmount.negate(),
                vatAmount = row.vatAmount.negate(),
                exemptionReasonCode = row.exemptionReasonCode,
                exemptionReasonText = row.exemptionReasonText,
            )
        }

    private fun validatedReason(request: DocumentDto.CancelInvoiceRequestDto): String {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        }
        return request.reason.trim()
    }

    /** The entity is the ORIGINAL — the invoice acted on — and the name carries the why. */
    private fun auditEntityOf(
        invoiceId: Long,
        correction: DocumentDto.InvoiceDto,
    ): AuditEntityRef =
        AuditEntityRef(
            id = invoiceId.toString(),
            name =
                "Invoice ${correction.precedingInvoiceReference ?: invoiceId} cancelled by correction " +
                    "${correction.id}: ${correction.booking?.bookingText.orEmpty()}",
        )

    private fun notFound(id: Long) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Invoice with id=$id not found")

    private fun notCancellable(detail: String) = PosException(PosErrorCode.INVOICE_NOT_CANCELLABLE, HttpStatusCode.Conflict, detail)
}
