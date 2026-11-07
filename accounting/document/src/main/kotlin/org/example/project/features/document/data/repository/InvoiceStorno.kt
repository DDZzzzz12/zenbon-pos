package org.example.project.features.document.data.repository

import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.domain.models.Invoice
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq

/**
 * How a cancelled incoming invoice and its correction (Storno, T9-19) find each other — defined once.
 *
 * An incoming invoice is never deleted; the operator cancels it by a **correction**: a second
 * `invoice` row carrying the original's totals negated, written by `InvoiceCancellationServiceImpl`.
 * The schema has no "cancelled" column and none may be added for this, so the pair is expressed in
 * columns that already exist:
 *
 * - the correction's [extractionSource][InvoiceTable.extractionSource] is [EXTRACTION_SOURCE] — the
 *   one value only this system writes. The type code alone would not do: a supplier's own `381`
 *   Gutschrift or `384` Korrektur parsed from XML is a real document, not our Storno;
 * - it references the **same Beleg** (`source_document_id`) — the paper that proves the original is
 *   also the evidence for its reversal, so a Storno is only possible on an invoice that has one;
 * - its BT-25 (`preceding_invoice_reference`) is the original's invoice number, null-safe equal, and
 *   its id is higher than the original's.
 *
 * `source_document_id` is non-unique — one scan may carry several invoices — so the number is what
 * tells them apart. Both halves are frozen once paired (the service refuses content and booking
 * writes), so neither the number nor the document can drift and break the link afterwards.
 *
 * Every function needs an ambient transaction.
 */
object InvoiceStorno {
    /** The `extraction_source` of a correction. Fits the column's 20 characters. */
    const val EXTRACTION_SOURCE = "STORNO"

    /** BT-3 of a correction: `381`, as the outgoing side's Gutschrift uses for its Storno. */
    const val TYPE_CODE = "381"

    fun isCorrection(invoice: Invoice): Boolean = invoice.extractionSource == EXTRACTION_SOURCE

    /** The correction that cancels [original], or null while it is live (or is a correction itself). */
    fun correctionOf(original: Invoice): Invoice? {
        if (isCorrection(original)) return null
        val documentId = original.sourceDocument?.id?.value ?: return null
        return Invoice
            .find {
                (InvoiceTable.sourceDocumentId eq documentId) and
                    (InvoiceTable.extractionSource eq EXTRACTION_SOURCE) and
                    precedingReferenceIs(original.invoiceNumber) and
                    (InvoiceTable.id greater original.id.value)
            }.orderBy(InvoiceTable.id to SortOrder.ASC)
            .firstOrNull()
    }

    /** The original a [correction] cancels, or null when [correction] is not one. */
    fun originalOf(correction: Invoice): Invoice? {
        if (!isCorrection(correction)) return null
        val documentId = correction.sourceDocument?.id?.value ?: return null
        return Invoice
            .find {
                (InvoiceTable.sourceDocumentId eq documentId) and
                    (InvoiceTable.extractionSource neq EXTRACTION_SOURCE) and
                    invoiceNumberIs(correction.precedingInvoiceReference) and
                    (InvoiceTable.id less correction.id.value)
            }.orderBy(InvoiceTable.id to SortOrder.ASC)
            .firstOrNull()
    }

    /**
     * The (original, correction) pair [invoice] belongs to, from either side; null for a live
     * invoice.
     */
    fun pairOf(invoice: Invoice): Pair<Invoice, Invoice>? =
        if (isCorrection(invoice)) {
            originalOf(invoice)?.let { it to invoice }
        } else {
            correctionOf(invoice)?.let { invoice to it }
        }

    /** Either half of a Storno: frozen against content and booking writes, and never an open item. */
    fun isFrozen(invoice: Invoice): Boolean = isCorrection(invoice) || correctionOf(invoice) != null

    /**
     * The ids among [invoices] that are either half of a Storno — one query for the whole batch, for
     * the DATEV AP export, which must pass neither on as a booking.
     */
    fun frozenIdsAmong(invoices: List<Invoice>): Set<Long> = invoices.filter { isCorrection(it) }.map { it.id.value }.toSet() + cancelledIdsAmong(invoices)

    /**
     * The ids among [invoices] whose Storno exists — the cancelled **originals** only, not the
     * corrections. One query for the whole batch; the Rechnungen list marks these "storniert".
     */
    fun cancelledIdsAmong(invoices: List<Invoice>): Set<Long> {
        val documentIds = invoices.mapNotNull { it.readValues[InvoiceTable.sourceDocumentId]?.value }.distinct()
        if (documentIds.isEmpty()) return emptySet()
        val known =
            Invoice
                .find {
                    (InvoiceTable.sourceDocumentId inList documentIds) and
                        (InvoiceTable.extractionSource eq EXTRACTION_SOURCE)
                }.toList()
        return invoices
            .filterNot { isCorrection(it) }
            .filter { original ->
                val documentId = original.readValues[InvoiceTable.sourceDocumentId]?.value
                known.any { correction ->
                    correction.readValues[InvoiceTable.sourceDocumentId]?.value == documentId &&
                        correction.precedingInvoiceReference == original.invoiceNumber &&
                        correction.id.value > original.id.value
                }
            }.map { it.id.value }
            .toSet()
    }

    private fun precedingReferenceIs(number: String?): Op<Boolean> =
        if (number == null) {
            InvoiceTable.precedingInvoiceReference.isNull()
        } else {
            InvoiceTable.precedingInvoiceReference eq number
        }

    private fun invoiceNumberIs(number: String?): Op<Boolean> =
        if (number == null) {
            InvoiceTable.invoiceNumber.isNull()
        } else {
            InvoiceTable.invoiceNumber eq number
        }
}
