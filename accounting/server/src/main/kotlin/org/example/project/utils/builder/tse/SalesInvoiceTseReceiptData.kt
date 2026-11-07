package org.example.project.utils.builder.tse

import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.export.dsfinvk.qrcode.DsfinvkQrCodeGenerator
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.utils.formatter.TseSerialFormat

/**
 * The readable TSE data of a from-bill invoice, read off the invoice's own snapshot.
 *
 * Every value comes from the snapshot's structured columns except the ClientID, which the snapshot
 * has no column for: that one is the `kassen-seriennummer` field of the stored QR payload
 * (`tse_qr_code_payload`), the only place it was frozen. The payload is also the NORMAL-mode
 * witness — it is written only for a transaction [toTseQrCodeContent] would print — so an invoice
 * without one prints the Ausfall disclosure rather than values from a simulated signature.
 *
 * Times resolve through [resolveStartLogTimeSeconds]/[resolveEndLogTimeSeconds], the order the
 * receipt's QR used, and the serial through [TseSerialFormat.normalizeToHex], the fallback
 * [org.example.project.utils.formatter.serialHex] uses.
 */
fun SalesInvoice.toTseReceiptData(): TseReceiptDataDto? {
    val clientId = clientIdFromQrPayload(this.tseQrCodePayload) ?: return null
    val transactionNumber = this.tseTransactionNumber ?: return null
    val signatureCounter = this.tseSignatureCounter ?: return null
    val signature = this.tseSignature?.takeIf { it.isNotEmpty() } ?: return null
    val serial = TseSerialFormat.normalizeToHex(this.tseSerialNumber)?.takeIf { it.isNotEmpty() } ?: return null
    val start = resolveStartLogTimeSeconds(this.tseStartTimestamp, this.tseTimestamp, this.tseStartTime) ?: return null
    val end = resolveEndLogTimeSeconds(this.tseTimestamp, this.tseEndTime) ?: return null
    return TseReceiptDataDto(
        transactionNumber = transactionNumber,
        startTime = DsfinvkQrCodeGenerator.formatTimestamp(start),
        endTime = DsfinvkQrCodeGenerator.formatTimestamp(end),
        signatureCounter = signatureCounter,
        tseSerialNumber = serial,
        clientSerialNumber = clientId,
        signature = signature,
    )
}
