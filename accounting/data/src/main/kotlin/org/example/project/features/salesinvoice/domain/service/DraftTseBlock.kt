package org.example.project.features.salesinvoice.domain.service

import kotlinx.datetime.LocalDateTime

/**
 * The originating bill's TSE signature, carried onto the invoice.
 *
 * §6 KassenSichV: the Beleg for a sale discloses this, and when the guest leaves holding the
 * Rechnung, the Rechnung is that document. Null when the bill was never signed — the TSE was down —
 * in which case the invoice prints the same Ausfall disclosure the receipt printed rather than
 * silently omitting the block.
 */
data class DraftTseBlock(
    val serialNumber: String?,
    val transactionNumber: Long?,
    val signatureCounter: Long?,
    val signature: String?,
    val signatureAlgorithm: String?,
    val logTimeFormat: String?,
    val processType: String?,
    val startTime: LocalDateTime?,
    val endTime: LocalDateTime?,
    val timestamp: Long?,
    val startTimestamp: Long?,
)
