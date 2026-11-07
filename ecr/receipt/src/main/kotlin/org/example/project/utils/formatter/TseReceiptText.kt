package org.example.project.utils.formatter

import org.example.project.core.domain.dto.tse.TseReceiptDataDto

/**
 * The one definition of the readable TSE block every Beleg prints — the ESC/POS receipt, the PDF
 * receipt and the A4 invoice from a bill all render these rows, so their field list and order
 * cannot drift apart.
 *
 * AEAO zu § 146a AO, Nr. 2.4.4 lists what a Beleg must carry; § 6 KassenSichV lets the values be
 * printed readably *or* inside a DSFinV-K Anhang I QR code. This system prints them readably and
 * no longer prints the QR. The AEAO then requires the values "in dem Format …, in dem sie von der
 * TSE an das elektronische Aufzeichnungssystem zurückgeliefert wurden" — no rounding, truncating or
 * altering. So a value is never shortened: a value too long for the paper is broken across lines
 * at exactly [width] characters, with nothing inserted or dropped, and joining those lines gives
 * the stored value back.
 */
object TseReceiptText {
    const val LABEL_TRANSACTION = "TSE-Transaktion"
    const val LABEL_START = "Start (UTC)"
    const val LABEL_END = "Ende (UTC)"
    const val LABEL_SIGNATURE_COUNTER = "Signaturzähler"
    const val LABEL_TSE_SERIAL = "TSE-Seriennr."
    const val LABEL_CLIENT_SERIAL = "Kassen-Seriennr."
    const val LABEL_SIGNATURE = "Signatur"

    /** Label/value pairs in print order; values verbatim from [data]. */
    fun rows(data: TseReceiptDataDto): List<Pair<String, String>> =
        listOf(
            LABEL_TRANSACTION to data.transactionNumber.toString(),
            LABEL_START to data.startTime,
            LABEL_END to data.endTime,
            LABEL_SIGNATURE_COUNTER to data.signatureCounter.toString(),
            LABEL_TSE_SERIAL to data.tseSerialNumber,
            LABEL_CLIENT_SERIAL to data.clientSerialNumber,
            LABEL_SIGNATURE to data.signature,
        )

    /** Every row of [data] laid out for a line of [width] characters (see [rowLines]). */
    fun lines(
        data: TseReceiptDataDto,
        width: Int,
    ): List<String> = rows(data).flatMap { (label, value) -> rowLines(label, value, width) }

    /**
     * `label: value` on one line when it fits [width]; otherwise `label:` alone, followed by the
     * value cut into [width]-character pieces. Cut, not word-wrapped: a Base64 signature or a hex
     * serial has no spaces, and a word wrapper would either overflow the paper or drop characters.
     */
    fun rowLines(
        label: String,
        value: String,
        width: Int,
    ): List<String> {
        require(width > 0) { "width must be positive, was $width" }
        val single = "$label: $value"
        if (single.length <= width) return listOf(single)
        return listOf("$label:") + value.chunked(width)
    }
}
