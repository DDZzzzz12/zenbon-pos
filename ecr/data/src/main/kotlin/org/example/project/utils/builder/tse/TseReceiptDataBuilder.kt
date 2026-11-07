package org.example.project.utils.builder.tse

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.export.dsfinvk.qrcode.DsfinvkQrCodeGenerator
import org.example.project.features.tse.domain.models.TseTransaction

/**
 * The readable TSE data of a bill's Beleg (AEAO zu § 146a AO, Nr. 2.4.4).
 *
 * Null under exactly the conditions [toTseQrCodeContent] is null: not NORMAL mode (a SIMULATION
 * signature is fabricated and must never be printed — B2), or a field the TSE did not return.
 */
fun TseTransaction.toTseReceiptData(): TseReceiptDataDto? {
    if (this.tseMode != TseMode.NORMAL) return null
    return this.toQrCodeDataDto()?.toTseReceiptData()
}

/**
 * The QR data's own fields, re-rendered as readable values. The times go through the very
 * [DsfinvkQrCodeGenerator.formatTimestamp] that wrote them into the QR payload, so the printed
 * `Start (UTC)`/`Ende (UTC)` are byte-identical to what the QR carried; the ClientID is the QR's
 * `kassen-seriennummer`.
 */
fun BillDto.TseQrCodeDataDto.toTseReceiptData(): TseReceiptDataDto =
    TseReceiptDataDto(
        transactionNumber = this.transactionNumber,
        startTime = DsfinvkQrCodeGenerator.formatTimestamp(this.startTimeSeconds),
        endTime = DsfinvkQrCodeGenerator.formatTimestamp(this.logTimeSeconds),
        signatureCounter = this.signatureCounter,
        tseSerialNumber = this.tseSerialNumber,
        clientSerialNumber = this.kassenSeriennummer,
        signature = this.signature,
    )

/** Field 2 of a DSFinV-K Anhang I payload (`V0;<kassen-seriennummer>;…`); null if not one. */
fun clientIdFromQrPayload(payload: String?): String? {
    val fields = payload?.split(DsfinvkQrCodeGenerator.FIELD_SEPARATOR) ?: return null
    if (fields.size < QR_FIELD_COUNT || fields[0] != DsfinvkQrCodeGenerator.QR_CODE_VERSION) return null
    return fields[1].takeIf { it.isNotEmpty() }
}

/** `V0` + eleven data fields — `processData` holds no `;` (it uses `^`, `_` and `:`). */
private const val QR_FIELD_COUNT = 12
