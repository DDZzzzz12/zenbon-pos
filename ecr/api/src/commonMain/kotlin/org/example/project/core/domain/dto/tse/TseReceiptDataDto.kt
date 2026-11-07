package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable

/**
 * The TSE values a Beleg prints as readable text (AEAO zu § 146a AO, Nr. 2.4.4 — § 6 KassenSichV).
 *
 * Every value is carried exactly as the TSE returned it to the till, because the AEAO forbids
 * "nachträgliches Runden, Abschneiden oder Verändern". The times are the TSE's Unix times rendered
 * in UTC without a zone offset — the form the AEAO explicitly accepts and the very string the
 * DSFinV-K Anhang I QR code carried (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`). Built server-side from the
 * same fields the QR payload is built from, so the two cannot disagree.
 *
 * Null on a DTO means there is no printable TSE data: the Vorgang is unsigned (outage, pending
 * Nachsignierung) or was signed in SIMULATION mode, whose fabricated values must never reach a
 * Beleg.
 *
 * @property transactionNumber Transaktionsnummer (TANR) of the Vorgang.
 * @property startTime Vorgangsbeginn — the StartTransaction log time, UTC, ISO 8601.
 * @property endTime Vorgangsende — the FinishTransaction log time, UTC, ISO 8601.
 * @property signatureCounter Signaturzähler of the FinishTransaction.
 * @property tseSerialNumber Seriennummer der TSE, hex-encoded as in DSFinV-K `tse.csv` `TSE_SERIAL`.
 * @property clientSerialNumber Seriennummer des elektronischen Aufzeichnungssystems: the ClientID
 *   the TSE logged for this Vorgang — the `kassen-seriennummer` field of the Anhang I QR code.
 * @property signature Prüfwert: the full Base64 signature value of the FinishTransaction.
 */
@Serializable
data class TseReceiptDataDto(
    val transactionNumber: Long,
    val startTime: String,
    val endTime: String,
    val signatureCounter: Long,
    val tseSerialNumber: String,
    val clientSerialNumber: String,
    val signature: String,
)
