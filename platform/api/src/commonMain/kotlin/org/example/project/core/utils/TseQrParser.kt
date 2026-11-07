package org.example.project.core.utils

/**
 * Parsed fields from a DSFinV-K TSE QR code content string.
 *
 * The QR content follows the format:
 * `V0;<kassenSeriennummer>;<processType>;<processData>;
 *  <transactionNumber>;<signatureCounter>;<startTime>;
 *  <logTime>;<signatureAlgorithm>;unixTime;<signature>;<publicKey>`
 *
 * Note: [processData] can itself contain semicolons
 * (e.g. Bestellung items: `2;"Mittagsbuffet";33.00`), so a naive
 * `split(";")` is not sufficient. This parser extracts the known-format
 * tail fields from the right and treats everything between [processType]
 * and the tail as [processData].
 */
data class TseQrParsed(
    val version: String,
    val kassenSeriennummer: String,
    val processType: String,
    val processData: String,
    val transactionNumber: String,
    val signatureCounter: String,
    val startTime: String,
    val logTime: String,
    val signatureAlgorithm: String,
    val logTimeFormat: String,
    val signature: String,
    val publicKey: String,
)

/** Number of fixed-format fields at the tail of the QR string. */
private const val TAIL_FIELD_COUNT = 8

/**
 * Parses a DSFinV-K TSE QR code content string into its individual fields.
 *
 * The first three fields (V0, kassenSeriennummer, processType) and the last
 * eight fields (transactionNumber through publicKey) are fixed-format and
 * never contain semicolons. Everything between processType and the tail is
 * the [TseQrParsed.processData], which is joined back with `;` when it
 * contains embedded separators (e.g. Bestellung item lists).
 *
 * Returns `null` if the string has fewer than `3 + TAIL_FIELD_COUNT + 1`
 * parts (minimum: V0, serial, processType, empty processData, + 8 tail).
 */
fun parseTseQrContent(qrContent: String): TseQrParsed? {
    val parts = qrContent.split(";")
    val minParts = 3 + TAIL_FIELD_COUNT // V0, serial, processType, _8 tail fields (processData can be empty = 0 extra parts)
    if (parts.size < minParts) return null

    // First three fields: never contain semicolons
    val version = parts[0]
    val kassenSeriennummer = parts[1]
    val processType = parts[2]

    // Last eight fields: fixed format, never contain semicolons
    val tailStart = parts.size - TAIL_FIELD_COUNT
    val transactionNumber = parts[tailStart]
    val signatureCounter = parts[tailStart + 1]
    val startTime = parts[tailStart + 2]
    val logTime = parts[tailStart + 3]
    val signatureAlgorithm = parts[tailStart + 4]
    val logTimeFormat = parts[tailStart + 5]
    val signature = parts[tailStart + 6]
    val publicKey = parts[tailStart + 7]

    // Everything between processType and the tail IS processData
    val processData =
        if (tailStart > 3) {
            parts.subList(3, tailStart).joinToString(";")
        } else {
            ""
        }

    return TseQrParsed(
        version = version,
        kassenSeriennummer = kassenSeriennummer,
        processType = processType,
        processData = processData,
        transactionNumber = transactionNumber,
        signatureCounter = signatureCounter,
        startTime = startTime,
        logTime = logTime,
        signatureAlgorithm = signatureAlgorithm,
        logTimeFormat = logTimeFormat,
        signature = signature,
        publicKey = publicKey,
    )
}
