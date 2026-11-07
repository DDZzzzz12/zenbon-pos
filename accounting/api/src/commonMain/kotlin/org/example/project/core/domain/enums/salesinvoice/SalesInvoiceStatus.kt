package org.example.project.core.domain.enums.salesinvoice

/**
 * The lifecycle of an outgoing invoice.
 *
 * There is no `DELETED`. A [DRAFT] has consumed no number and can be removed outright; an [ISSUED]
 * invoice can only be left by issuing a 381 Gutschrift, which is what sets [CANCELLED]. Deleting an
 * issued invoice would leave a gap in the §14 UStG sequence.
 */
enum class SalesInvoiceStatus {
    DRAFT,
    ISSUED,
    CANCELLED,
}
