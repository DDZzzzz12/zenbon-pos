package org.example.project.export.dsfinvk.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * DSFinV-K compliant QR code generator for German fiscal receipts.
 *
 * Generates QR codes according to the DSFinV-K specification (Anhang I)
 * for machine-readable cash register receipts as required by KassenSichV.
 *
 * QR Code Format (fields separated by semicolon):
 * ```
 * <qr-code-version>;<kassen-seriennummer>;<processType>;<processData>;
 * <transaktions-nummer>;<signatur-zaehler>;<start-zeit>;<log-time>;
 * <sig-alg>;<log-time-format>;<signatur>;<public-key>
 * ```
 *
 * Recommendations from DSFinV-K specification:
 * - Minimum edge length: 3 cm (~354 pixels at 300 dpi)
 * - Print quality: at least 300 dpi
 * - High error correction level recommended
 *
 * @see <a href="https://www.bzst.de/DE/Unternehmen/Aussenpruefungen/DigitaleSchnittstelleFinanzverwaltung/">DSFinV-K</a>
 */
object DsfinvkQrCodeGenerator {
    /**
     * Current QR code version as per DSFinV-K specification.
     * Always "V0" in current spec version.
     */
    const val QR_CODE_VERSION = "V0"

    /**
     * Separator character for QR code fields (Unicode U+003B).
     */
    const val FIELD_SEPARATOR = ";"

    /**
     * Default log time format identifier.
     */
    const val LOG_TIME_FORMAT = "unixTime"

    /**
     * ISO 8601 date-time formatter for TSE timestamps.
     * Format: YYYY-MM-DDThh:mm:ss.fffZ
     */
    private val ISO_FORMATTER: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

    /**
     * Data class containing all fields required for DSFinV-K QR code generation.
     *
     * @property kassenSeriennummer Cash register serial number (Client-ID)
     * @property processType TSE process type (e.g., "Kassenbeleg-V1", "Bestellung-V1")
     * @property processData The signed process data in DSFinV-K format
     * @property transactionNumber TSE transaction number
     * @property signatureCounter Signature counter from FinishTransaction
     * @property startTime Unix timestamp (seconds) from StartTransaction
     * @property logTime Unix timestamp (seconds) from FinishTransaction
     * @property signatureAlgorithm Signature algorithm (e.g., "ecdsa-plain-SHA384")
     * @property signature Base64-encoded signature from FinishTransaction
     * @property publicKey Base64-encoded public key
     */
    data class QrCodeData(
        val kassenSeriennummer: String,
        val processType: String,
        val processData: String,
        val transactionNumber: Long,
        val signatureCounter: Long,
        val startTime: Long,
        val logTime: Long,
        val signatureAlgorithm: String,
        val signature: String,
        val publicKey: String,
    )

    /**
     * Generates the QR code content string according to DSFinV-K specification.
     *
     * The content is encoded in UTF-8 (same as processData) and consists of
     * fields separated by semicolons.
     *
     * @param data The QR code data containing all required fields
     * @return The formatted QR code content string
     */
    fun generateQrCodeContent(data: QrCodeData): String {
        // Convert timestamps to ISO 8601 format
        val startTimeFormatted = formatTimestamp(data.startTime)
        val logTimeFormatted = formatTimestamp(data.logTime)

        // Build QR code content according to DSFinV-K specification
        return buildString {
            append(QR_CODE_VERSION)
            append(FIELD_SEPARATOR)
            append(data.kassenSeriennummer)
            append(FIELD_SEPARATOR)
            append(data.processType)
            append(FIELD_SEPARATOR)
            append(data.processData)
            append(FIELD_SEPARATOR)
            append(data.transactionNumber)
            append(FIELD_SEPARATOR)
            append(data.signatureCounter)
            append(FIELD_SEPARATOR)
            append(startTimeFormatted)
            append(FIELD_SEPARATOR)
            append(logTimeFormatted)
            append(FIELD_SEPARATOR)
            append(data.signatureAlgorithm)
            append(FIELD_SEPARATOR)
            append(LOG_TIME_FORMAT)
            append(FIELD_SEPARATOR)
            append(data.signature)
            append(FIELD_SEPARATOR)
            append(data.publicKey)
        }
    }

    /**
     * Generates a QR code image as PNG bytes.
     *
     * DSFinV-K recommendations:
     * - Minimum edge length: 3 cm (~354 pixels at 300 dpi)
     * - Print quality: at least 300 dpi
     * - High error correction level
     *
     * @param content The QR code content string
     * @param size The size of the QR code in pixels (default: 400 for ~3.4cm at 300 dpi)
     * @param errorCorrectionLevel Error correction level (default: H for 30% recovery)
     * @return PNG image as byte array
     */
    fun generateQrCodeImageBytes(
        content: String,
        size: Int = 400,
        errorCorrectionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
    ): ByteArray {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to errorCorrectionLevel,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2,
            )

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val outputStream = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Generates a QR code and returns both content string and PNG bytes.
     *
     * @param data The QR code data containing all required fields
     * @param imageSize The size of the QR code image in pixels (default: 400)
     * @return Pair of (content string, PNG image bytes)
     */
    fun generateQrCode(
        data: QrCodeData,
        imageSize: Int = 400,
    ): Pair<String, ByteArray> {
        val content = generateQrCodeContent(data)
        val imageBytes = generateQrCodeImageBytes(content, imageSize)
        return content to imageBytes
    }

    /**
     * Formats a Unix timestamp (seconds since epoch) to ISO 8601 format.
     *
     * Output format: YYYY-MM-DDThh:mm:ss.fffZ
     *
     * Note: TSE hardware returns timestamps in seconds (Unix time), not milliseconds.
     *
     * @param timestampSeconds Unix timestamp in seconds
     * @return Formatted timestamp string
     */
    fun formatTimestamp(timestampSeconds: Long): String {
        val instant = Instant.ofEpochSecond(timestampSeconds)
        return ISO_FORMATTER.format(instant)
    }

    /**
     * Creates QrCodeData from TSE transaction data.
     *
     * Helper method to construct QrCodeData from typical TSE response fields.
     *
     * @param clientId Cash register Client-ID (Kassen-Seriennummer)
     * @param processType TSE process type
     * @param processData The signed process data
     * @param transactionNumber TSE transaction number
     * @param signatureCounter Signature counter
     * @param startTimeSeconds Start timestamp in seconds (Unix time from TSE)
     * @param finishTimeSeconds Finish timestamp in seconds (Unix time from TSE)
     * @param signatureAlgorithm Signature algorithm
     * @param signatureBase64 Base64-encoded signature
     * @param publicKeyBase64 Base64-encoded public key
     * @return QrCodeData ready for QR code generation
     */
    fun createQrCodeData(
        clientId: String,
        processType: String,
        processData: String,
        transactionNumber: Long,
        signatureCounter: Long,
        startTimeSeconds: Long,
        finishTimeSeconds: Long,
        signatureAlgorithm: String,
        signatureBase64: String,
        publicKeyBase64: String,
    ): QrCodeData =
        QrCodeData(
            kassenSeriennummer = clientId,
            processType = processType,
            processData = processData,
            transactionNumber = transactionNumber,
            signatureCounter = signatureCounter,
            startTime = startTimeSeconds,
            logTime = finishTimeSeconds,
            signatureAlgorithm = signatureAlgorithm,
            signature = signatureBase64,
            publicKey = publicKeyBase64,
        )
}
