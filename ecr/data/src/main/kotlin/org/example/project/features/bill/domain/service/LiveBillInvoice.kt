package org.example.project.features.bill.domain.service

import java.math.BigDecimal

/**
 * What the bill side needs to know about the live (non-cancelled) sales invoice of a bill: its id,
 * its number (null while a draft), what it still owes, and whether it has been issued.
 */
data class LiveBillInvoice(
    val id: Long,
    val invoiceNumber: String?,
    val openAmount: BigDecimal?,
    val issued: Boolean,
)
