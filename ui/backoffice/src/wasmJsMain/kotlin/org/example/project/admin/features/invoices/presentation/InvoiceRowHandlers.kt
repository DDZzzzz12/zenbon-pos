package org.example.project.admin.features.invoices.presentation

/**
 * What a Rechnungen row can do, bundled so the wide table and the phone list take one parameter
 * instead of four callbacks.
 *
 * [onCapture] opens a row's document in the capture form, [onOpenInvoice] opens an invoice that has
 * no document by its own id, [onMarkReviewed] confirms a captured invoice, [onAssign] leads to the
 * Zuordnung queue.
 */
data class InvoiceRowHandlers(
    val onCapture: (Long) -> Unit,
    val onOpenInvoice: (Long) -> Unit,
    val onMarkReviewed: (Long) -> Unit,
    val onAssign: () -> Unit,
)
