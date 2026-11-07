@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.utils.builder.tse

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.export.dsfinvk.qrcode.DsfinvkQrCodeGenerator
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.utils.formatter.getTimeZone
import org.example.project.utils.formatter.serialHex

fun TseTransaction.toTseQrCodeContent(): String? {
    if (this.tseMode != TseMode.NORMAL) return null
    val dto = this.toQrCodeDataDto() ?: return null
    val qrData = dto.toQrCodeData()
    return DsfinvkQrCodeGenerator.generateQrCodeContent(qrData)
}

fun TseTransaction.resolvedStartLogTimeSeconds(): Long? = resolveStartLogTimeSeconds(this.tseStartTimestamp, this.tseTimestamp, this.startTime)

/**
 * Vorgangsbeginn in Unix seconds: the TSE's StartTransaction log time, else its FinishTransaction
 * log time, else the till's own start time. The one resolution order for the QR payload, the
 * DSFinV-K `TSE_TA_START` and the readable Beleg text — the invoice snapshot carries the same
 * three columns and resolves through here too, so an invoice prints what its receipt printed.
 */
fun resolveStartLogTimeSeconds(
    tseStartTimestamp: Long?,
    tseTimestamp: Long?,
    startTime: LocalDateTime?,
): Long? =
    tseStartTimestamp
        ?: tseTimestamp
        ?: startTime?.toInstant(getTimeZone())?.epochSeconds

/** Vorgangsende in Unix seconds: the TSE's FinishTransaction log time, else the till's end time. */
fun resolveEndLogTimeSeconds(
    tseTimestamp: Long?,
    endTime: LocalDateTime?,
): Long? = tseTimestamp ?: endTime?.toInstant(getTimeZone())?.epochSeconds

fun TseTransaction.toQrCodeDataDto(): BillDto.TseQrCodeDataDto? {
    val txNumber = this.transactionNumber ?: return null
    val sigCounter = this.signatureCounter ?: return null
    val signature = this.signature ?: return null
    val processData = this.processDataHash ?: return null
    val processType = this.processType ?: return null
    val signatureAlgorithm = this.signatureAlgorithm ?: return null
    val publicKey = this.publicKey ?: return null
    val clientId = this.clientId ?: return null
    // The canonical hex serial (TR-03153-1 §9.3.2), not the raw column: a receipt names the device.
    val tseSerialNumber = this.serialHex() ?: return null

    val startTimeSeconds = this.resolvedStartLogTimeSeconds() ?: return null
    val logTimeSeconds = resolveEndLogTimeSeconds(this.tseTimestamp, this.endTime) ?: return null

    return BillDto.TseQrCodeDataDto(
        kassenSeriennummer = clientId,
        processType = processType,
        processData = processData,
        transactionNumber = txNumber,
        signatureCounter = sigCounter,
        startTimeSeconds = startTimeSeconds,
        logTimeSeconds = logTimeSeconds,
        signatureAlgorithm = signatureAlgorithm,
        signature = signature,
        publicKey = publicKey,
        tseSerialNumber = tseSerialNumber,
    )
}

private fun BillDto.TseQrCodeDataDto.toQrCodeData(): DsfinvkQrCodeGenerator.QrCodeData =
    DsfinvkQrCodeGenerator.QrCodeData(
        kassenSeriennummer = this.kassenSeriennummer,
        processType = this.processType,
        processData = this.processData,
        transactionNumber = this.transactionNumber,
        signatureCounter = this.signatureCounter,
        startTime = this.startTimeSeconds,
        logTime = this.logTimeSeconds,
        signatureAlgorithm = this.signatureAlgorithm,
        signature = this.signature,
        publicKey = this.publicKey,
    )
