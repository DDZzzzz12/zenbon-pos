package org.example.project.admin.core.presentation.belegfluss

/**
 * The one primary action of a Rechnungen row, chosen by the row's state: capture a scan, review a
 * captured invoice, assign a payment to an open one, or just open a finished one.
 */
enum class InvoiceRowAction {
    CAPTURE,
    REVIEW,
    ASSIGN,
    OPEN,
}
