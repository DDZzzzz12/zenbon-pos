package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One open case-C (STANDALONE) invoice the CREDIT ladder can match against.
 *
 * The AR mirror of [InvoiceCandidate]: same reference keys (`invoice_number` + `payment_reference`),
 * but the counterparty is a **debtor** rather than a supplier. [debtorId] is null for a one-off
 * guest whose address was typed rather than picked from master data — signals that need a name key
 * simply skip such a candidate, because there is no record to agree with.
 */
data class SalesInvoiceCandidate(
    val id: Long,
    val invoiceNumber: String? = null,
    val paymentReference: String? = null,
    val debtorId: Long? = null,
    val issueDate: LocalDate? = null,
    val openAmount: BigDecimal? = null,
    val dueDate: LocalDate? = null,
)
