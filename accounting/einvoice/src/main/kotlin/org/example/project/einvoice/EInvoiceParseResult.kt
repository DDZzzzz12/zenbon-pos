package org.example.project.einvoice

import org.example.project.einvoice.model.EInvoice

/**
 * Malformed third-party input is an expected condition, not an exceptional one — a supplier can and
 * will send a broken file. Parsers therefore return a failure value rather than throwing, so the
 * caller must handle it.
 */
sealed interface EInvoiceParseResult {
    data class Success(
        val invoice: EInvoice,
    ) : EInvoiceParseResult

    /** No structured payload was found — e.g. a plain scanned PDF. Not an error. */
    data object NoEInvoiceFound : EInvoiceParseResult

    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
    ) : EInvoiceParseResult

    /**
     * A structured payload was found and is well-formed, but its profile is not an EN 16931
     * invoice — ZUGFeRD `MINIMUM` and `BASIC WL` are booking aids, not invoices. Distinct from
     * [Failure] because nothing is broken: the supplier sent exactly what they meant to.
     */
    data class NotConformant(
        val profile: String,
        val reason: String,
    ) : EInvoiceParseResult
}
