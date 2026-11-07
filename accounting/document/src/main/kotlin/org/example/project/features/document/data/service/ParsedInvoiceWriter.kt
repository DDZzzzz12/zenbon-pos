package org.example.project.features.document.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceAllowanceCharge
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.model.EInvoiceLine
import org.example.project.einvoice.model.EInvoiceVatBreakdown
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.document.domain.repository.DocumentRepository
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolution
import org.example.project.features.supplier.data.service.SupplierResolver
import org.slf4j.LoggerFactory

/**
 * Writes a parsed [EInvoice] into the normalized invoice model — the bridge between the
 * format-agnostic `:accounting-einvoice` parser and this project's persistence.
 *
 * The dependency runs one way only: this module may use `:accounting-einvoice`, never the reverse. The class
 * takes a repository and a model and nothing else — no Ktor, no Koin — so it can be exercised
 * directly from a test.
 *
 * ### Provenance
 * Everything written here is marked `extraction_source = EINVOICE_XML`. That is not decoration: it
 * records that the data came from a structured payload the supplier signed off on, rather than
 * from an OCR guess or an operator's typing, and the editor treats such an invoice as read-only on
 * exactly that basis. `e_invoice_format` records *which* payload.
 *
 * ### Re-parsing replaces, it does not duplicate
 * Keyed on [DocumentRepository.getInvoiceBySourceDocument]: an invoice already extracted from the
 * same source document is updated in place and its child rows replaced, matching the discipline
 * the legacy backfill uses. `source_document_id` is a plain index, not unique, so without this
 * check a second parse of one upload would leave two invoices behind and every downstream
 * consumer — document search, the DATEV AP export — would count the amount twice.
 *
 * An invoice with no source document (`sourceDocumentId == null`, a bare XRechnung XML) has no key
 * to match on and is always created.
 *
 * ### Full replace of the header
 * The update leg goes through [DocumentRepository.updateInvoice], which replaces every
 * supplier-asserted field including the nulls. That is correct here — the parse result is a
 * complete picture of the invoice — and it means a term that disappeared from the re-issued XML is
 * cleared rather than left stale. Booking fields are untouched: they live on a separate table
 * precisely so a re-parse cannot erase an operator's bookkeeping decision.
 *
 * Requires an ambient transaction — it reads and writes entities directly, like the repository it
 * calls.
 */
class ParsedInvoiceWriter(
    private val repository: DocumentRepository,
    private val supplierResolver: SupplierResolver =
        SupplierResolver(SupplierRepositoryImpl()),
) {
    private val logger = LoggerFactory.getLogger(ParsedInvoiceWriter::class.java)

    companion object {
        /**
         * The `InvoiceTable.extractionSource` value marking data as coming from structured XML.
         *
         * `internal` rather than private because `LegacyInvoiceSync` must recognise it to know which
         * invoices it may not overwrite. One home for the literal: a second copy that drifted would
         * silently disable that guard, and no test would fail.
         */
        internal const val EXTRACTION_SOURCE = "EINVOICE_XML"

        /**
         * UNTDID 1001 `380`, commercial invoice — the column's own default, restated here because
         * `EInvoice.invoiceTypeCode` is nullable (a sender may state nothing) while the column is
         * `NOT NULL`. A document that states no type is an ordinary invoice.
         */
        private const val DEFAULT_INVOICE_TYPE_CODE = "380"
    }

    /**
     * Persists [invoice], linked to [sourceDocumentId] when the payload arrived inside a stored
     * file. Returns the invoice id, whether it was created or replaced.
     */
    fun write(
        invoice: EInvoice,
        sourceDocumentId: Long?,
    ): Long {
        val existing = sourceDocumentId?.let { repository.getInvoiceBySourceDocument(it) }
        val written = writeHeader(invoice, sourceDocumentId, existing)
        val invoiceId = written.id.value

        // Master data resolution is best-effort: an unresolvable creditor should not cost us the
        // invoice, which is still good on its own and can be linked by hand later.
        //
        // "Should not" rather than "cannot": this runs inside the caller's transaction, so a
        // resolver failure that is a *database* error — a unique-violation on `supplier.vat_id`
        // from two concurrent uploads of the same new creditor, say — leaves the transaction
        // poisoned and the `replace…` calls below will fail regardless of this catch. The blast
        // radius is bounded: parsing already runs in its own transaction after the document is
        // committed, so the upload survives and the document lands in `FAILED`. Making resolution
        // genuinely unable to fail the parse would need a savepoint around it.
        runCatching { supplierResolver.resolveAndLink(written) }
            .onSuccess { reportUnknownIban(invoiceId, invoice.paymentIban, it) }
            .onFailure { logger.warn("Supplier resolution failed for invoice {}", invoiceId, it) }

        repository.replaceVatBreakdown(invoiceId, invoice.vatBreakdown.map(::toVatRow))
        repository.replaceInvoiceLines(invoiceId, invoice.lines.map(::toLineRow))
        repository.replaceAllowancesCharges(invoiceId, invoice.allowancesCharges.map(::toAllowanceChargeRow))

        return invoiceId
    }

    /**
     * An invoice quoting an IBAN this creditor has never used is the payment-redirection pattern:
     * the supplier's name and VAT ID check out, only the bank account moved. The resolver registers
     * the IBAN regardless — suppliers do change banks, and refusing the invoice would be wrong — so
     * the log line is what survives the transaction.
     *
     * **Interim carrier of the signal.** The capture screen that shows this to an operator before
     * they pay is a later slice; until it lands, the WARN in the server log is the only place the
     * warning exists, so it is deliberately loud and carries every identifier needed to find the
     * document by hand.
     */
    private fun reportUnknownIban(
        invoiceId: Long,
        iban: String?,
        resolution: SupplierResolution,
    ) {
        if (!resolution.ibanWasUnknown) return
        logger.warn(
            "Invoice {} quotes IBAN {} for supplier {}, which that supplier has never used before — " +
                "verify the bank details over a known phone number before paying (payment-redirection risk).",
            invoiceId,
            iban,
            resolution.supplier?.id?.value,
        )
    }

    /**
     * The create and update argument lists are kept adjacent so a term added to one is visibly
     * missing from the other. `updateInvoice` takes no defaults by design, and both legs stamp
     * `extractionSource` — the update leg may be replacing a row captured by hand as `MANUAL`, so
     * provenance has to move with the content. `sourceDocumentId` is identity rather
     * than content and remains reachable only from the create leg.
     */
    private fun writeHeader(
        invoice: EInvoice,
        sourceDocumentId: Long?,
        existing: Invoice?,
    ): Invoice =
        if (existing == null) {
            repository.createInvoice(
                sourceDocumentId = sourceDocumentId,
                eInvoiceFormat = formatCode(invoice.format),
                extractionSource = EXTRACTION_SOURCE,
                invoiceNumber = invoice.invoiceNumber,
                issueDate = invoice.issueDate.atStartOfDay(),
                dueDate = invoice.dueDate?.atStartOfDay(),
                deliveryDate = invoice.deliveryDate?.atStartOfDay(),
                currency = invoice.currency,
                invoiceTypeCode = invoice.invoiceTypeCode ?: DEFAULT_INVOICE_TYPE_CODE,
                precedingInvoiceReference = invoice.precedingInvoiceReference,
                buyerReference = invoice.buyerReference,
                sellerName = invoice.seller.name,
                sellerVatId = invoice.seller.vatId,
                sellerTaxNumber = invoice.seller.taxNumber,
                sellerStreet = invoice.seller.street,
                sellerZip = invoice.seller.zip,
                sellerCity = invoice.seller.city,
                sellerCountry = invoice.seller.country,
                sellerPhone = invoice.seller.phone,
                sellerEmail = invoice.seller.email,
                paymentIban = invoice.paymentIban,
                paymentReference = invoice.paymentReference,
                paymentTermsText = invoice.paymentTermsText,
                lineNetTotal = invoice.lineNetTotal,
                taxExclusiveTotal = invoice.taxExclusiveTotal,
                taxTotal = invoice.taxTotal,
                taxInclusiveTotal = invoice.taxInclusiveTotal,
                amountDue = invoice.amountDue,
            )
        } else {
            repository.updateInvoice(
                invoiceId = existing.id.value,
                eInvoiceFormat = formatCode(invoice.format),
                // Provenance travels with the content. The row being updated may be a MANUAL one an
                // operator typed through `POST /api/documents/{id}/invoice`; every one of its
                // fields is about to be replaced by a value read out of the supplier's XML, so the
                // label has to follow, or `updateInvoiceSupplierContent`'s read-only guard — which
                // keys on EINVOICE_XML — stays off on parsed content.
                extractionSource = EXTRACTION_SOURCE,
                invoiceNumber = invoice.invoiceNumber,
                issueDate = invoice.issueDate.atStartOfDay(),
                dueDate = invoice.dueDate?.atStartOfDay(),
                deliveryDate = invoice.deliveryDate?.atStartOfDay(),
                currency = invoice.currency,
                invoiceTypeCode = invoice.invoiceTypeCode ?: DEFAULT_INVOICE_TYPE_CODE,
                precedingInvoiceReference = invoice.precedingInvoiceReference,
                buyerReference = invoice.buyerReference,
                sellerName = invoice.seller.name,
                sellerVatId = invoice.seller.vatId,
                sellerTaxNumber = invoice.seller.taxNumber,
                sellerStreet = invoice.seller.street,
                sellerZip = invoice.seller.zip,
                sellerCity = invoice.seller.city,
                sellerCountry = invoice.seller.country,
                sellerPhone = invoice.seller.phone,
                sellerEmail = invoice.seller.email,
                paymentIban = invoice.paymentIban,
                paymentReference = invoice.paymentReference,
                paymentTermsText = invoice.paymentTermsText,
                lineNetTotal = invoice.lineNetTotal,
                taxExclusiveTotal = invoice.taxExclusiveTotal,
                taxTotal = invoice.taxTotal,
                taxInclusiveTotal = invoice.taxInclusiveTotal,
                amountDue = invoice.amountDue,
            )
        }

    /**
     * The `e_invoice_format` column's declared domain is `zugferd | factur-x | xrechnung | ubl`,
     * which is the lowercased enum name with the underscore written as the hyphen the format's own
     * name carries. A plain `name.lowercase()` would store `factur_x` and fall outside that domain.
     */
    private fun formatCode(format: EInvoiceFormat): String = format.name.lowercase().replace('_', '-')

    /** The model carries dates as [LocalDate]; the columns are `datetime`, so midnight it is. */
    private fun LocalDate.atStartOfDay(): LocalDateTime = atTime(0, 0)

    private fun toVatRow(row: EInvoiceVatBreakdown) =
        DocumentRepository.VatBreakdownRow(
            vatCategoryCode = row.categoryCode,
            vatRate = row.rate,
            taxableAmount = row.taxableAmount,
            vatAmount = row.vatAmount,
            exemptionReasonCode = row.exemptionReasonCode,
            exemptionReasonText = row.exemptionReasonText,
            // `description` is the per-line DATEV booking-text override, an internal bookkeeping
            // decision. An e-invoice has no such term, so it stays null rather than borrowing the
            // exemption text.
        )

    private fun toLineRow(line: EInvoiceLine) =
        DocumentRepository.InvoiceLineRow(
            lineId = line.lineId,
            itemName = line.itemName,
            quantity = line.quantity,
            unitCode = line.unitCode,
            netUnitPrice = line.netUnitPrice,
            lineNetAmount = line.lineNetAmount,
            vatCategoryCode = line.vatCategoryCode,
            vatRate = line.vatRate,
            itemDescription = line.itemDescription,
            sellerItemId = line.sellerItemId,
            buyerItemId = line.buyerItemId,
            itemStandardId = line.itemStandardId,
        )

    private fun toAllowanceChargeRow(row: EInvoiceAllowanceCharge) =
        DocumentRepository.AllowanceChargeRow(
            isCharge = row.isCharge,
            amount = row.amount,
            vatCategoryCode = row.vatCategoryCode,
            vatRate = row.vatRate,
            baseAmount = row.baseAmount,
            percentage = row.percentage,
            reasonCode = row.reasonCode,
            reasonText = row.reasonText,
        )
}
