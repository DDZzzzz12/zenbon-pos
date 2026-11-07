package org.example.project.einvoice.detect

/**
 * Raised when a container could not be opened far enough to answer whether it holds an e-invoice.
 *
 * Deliberately distinct from "no payload found", which [EInvoicePayloadLocator] signals with null.
 * A scanned PDF genuinely has no attachment; a corrupt or oversized one *might* have had, and the
 * difference decides whether the document may be filed away as a plain scan or must be surfaced to
 * an operator. Collapsing both into null would silently drop invoices a supplier really did send.
 */
class EInvoicePayloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
