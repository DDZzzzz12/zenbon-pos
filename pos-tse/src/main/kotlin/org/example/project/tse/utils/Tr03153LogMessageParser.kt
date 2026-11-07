/**
 * TR-03153 ASN.1 DER log message parser for TSE log entries.
 *
 * Provides best-effort parsing of TSE log messages which are ASN.1 DER
 * encoded according to TR-03153 (BSI Technical Guideline for TSE).
 *
 * The log message structure (simplified):
 * SEQUENCE {
 *   version INTEGER,
 *   certifiedDataType ObjectIdentifier,
 *   certifiedData (depends on type),
 *   serialNumber OCTET STRING,
 *   signatureAlgorithm ObjectIdentifier,
 *   signatureCounter INTEGER,
 *   logTime OCTET STRING (GeneralizedTime or UnixTime),
 *   signatureValue OCTET STRING
 * }
 *
 * This parser does manual TLV (Tag-Length-Value) parsing since the
 * structure is fixed/known and no external ASN.1 library is needed.
 *
 * @since 2.1.0
 */
package org.example.project.tse.utils

import org.example.project.core.domain.dto.tse.TseDto
import org.slf4j.LoggerFactory

/**
 * Parser for TR-03153 ASN.1 DER encoded TSE log messages.
 */
object Tr03153LogMessageParser {

    private val logger = LoggerFactory.getLogger(Tr03153LogMessageParser::class.java)

    // ASN.1 tags
    private const val TAG_SEQUENCE: Int = 0x30
    private const val TAG_BOOLEAN: Int = 0x01
    private const val TAG_INTEGER: Int = 0x02
    private const val TAG_OCTET_STRING: Int = 0x04
    private const val TAG_OID: Int = 0x06
    private const val TAG_ENUMERATED: Int = 0x0A
    private const val TAG_UTF8_STRING: Int = 0x0C
    private const val TAG_PRINTABLE_STRING: Int = 0x13
    private const val TAG_UTC_TIME: Int = 0x17
    private const val TAG_GENERALIZED_TIME: Int = 0x18

    // certifiedDataType OIDs (TR-03151 §A). The trailing arc identifies the log kind, which in
    // turn fixes what the IMPLICIT context tags in certifiedData mean.
    private const val OID_TRANSACTION_LOG = "0.4.0.127.0.7.3.7.1.1"
    private const val OID_SYSTEM_LOG = "0.4.0.127.0.7.3.7.1.2"
    private const val OID_AUDIT_LOG = "0.4.0.127.0.7.3.7.1.3"

    /**
     * Parse a TR-03153 log message byte array into structured fields.
     *
     * @param data Raw ASN.1 DER encoded log message bytes
     * @return Parsed log message DTO with best-effort field extraction
     */
    fun parse(data: ByteArray): TseDto.ParsedLogMessageDto? {
        if (data.isEmpty()) return null

        return try {
            var offset = 0

            // Outer SEQUENCE — step into its content.
            if (data[offset].toInt() and 0xFF != TAG_SEQUENCE) return null
            offset = readLength(data, offset + 1).second

            // version INTEGER
            val version = readIntegerIfTag(data, offset) ?: return null
            offset = version.second

            // certifiedDataType OID — decides how the certifiedData context tags are read.
            val certifiedDataType = readOidIfTag(data, offset)
            if (certifiedDataType != null) offset = certifiedDataType.second
            val cdt = certifiedDataType?.first

            // certifiedData: a run of IMPLICIT context-specific tags [0..n] sitting directly in
            // the outer SEQUENCE — NOT an inner SEQUENCE. Being IMPLICIT, each holds its field
            // content directly (a PrintableString's bytes, an INTEGER's bytes), with no inner
            // UNIVERSAL tag. Which tag means what depends on certifiedDataType (TR-03151-1 §2.2/2.3):
            //   transaction: [0] operationType [1] clientId [2] processData [3] processType
            //                [4] additionalExternalData [5] transactionNumber [6] additionalInternalData
            //   system (v3): [0] eventType [1] eventOrigin [2] eventTriggeredByUser
            //                [3] eventData(SEQUENCE) [4] additionalInternalData     -- TSE 2.0
            //   system (v2): [0] operationType [1] systemOperationData [2] additionalInternalData
            //   audit:       none — certifiedData is absent; seAuditData is a separate OPTIONAL
            //                envelope field after signatureAlgorithm, handled by the trailing scan.
            // [0] is read as operationType/eventType for every kind; for v3 system logs we also
            // surface eventOrigin[1], eventTriggeredByUser[2] and eventData[3]. Those tags mean
            // something else in v2 (systemOperationData[1]/additionalInternalData[2]), so they are
            // only read when version >= 3 — leaving every real 1.x (v2) path unchanged. Every
            // context tag (incl. the v3 eventData SEQUENCE) is consumed whole, so its nested
            // integers never leak into the trailing scan.
            var operationType: String? = null
            var clientId: String? = null
            var processType: String? = null
            var transactionNumber: Long? = null
            var eventOrigin: String? = null
            var eventTriggeredByUser: String? = null
            var eventDataHex: String? = null
            var authenticationResult: String? = null
            var remainingRetries: Long? = null
            var authRole: String? = null

            while (offset < data.size && (data[offset].toInt() and 0xC0) == 0x80) {
                val tagNum = data[offset].toInt() and 0x1F
                val lenRes = readLength(data, offset + 1)
                val start = lenRes.second
                val end = (start + lenRes.first).coerceAtMost(data.size)
                val value = data.sliceArray(start until end)
                when (cdt) {
                    OID_TRANSACTION_LOG -> when (tagNum) {
                        0 -> operationType = value.toDecodedString()
                        1 -> clientId = value.toDecodedString()
                        3 -> processType = value.toDecodedString()
                        5 -> transactionNumber = value.toUnsignedLong()
                    }
                    OID_SYSTEM_LOG -> {
                        if (tagNum == 0) operationType = value.toDecodedString()
                        if (version.first >= 3) when (tagNum) {
                            1 -> eventOrigin = value.toDecodedString()
                            2 -> eventTriggeredByUser = value.toDecodedString()
                            3 -> {
                                eventDataHex = value.toHexString()
                                val et = operationType
                                if (et == "authenticateUser" || et == "authenticateSmaAdmin") {
                                    val (ar, rr, role) = decodeAuthEventData(value, et)
                                    authenticationResult = ar
                                    remainingRetries = rr
                                    authRole = role
                                }
                            }
                        }
                    }
                    OID_AUDIT_LOG -> Unit // [0] is seAuditData, not an operation type
                    else -> if (tagNum == 0) operationType = value.toDecodedString() // best effort
                }
                offset = end
            }

            // serialNumber OCTET STRING
            val serialNumber = readOctetStringIfTag(data, offset)
            var serialNumberHex: String? = null
            if (serialNumber != null) {
                serialNumberHex = serialNumber.first.toHexString()
                offset = serialNumber.second
            }

            // signatureAlgorithm: an AlgorithmIdentifier SEQUENCE { algorithm OID, parameters? },
            // not a bare OID. Read the inner OID and step past the whole SEQUENCE.
            var signatureAlgorithm: String? = null
            if (offset < data.size && data[offset].toInt() and 0xFF == TAG_SEQUENCE) {
                val seqLen = readLength(data, offset + 1)
                signatureAlgorithm = readOidIfTag(data, seqLen.second)?.first
                offset = (seqLen.second + seqLen.first).coerceAtMost(data.size)
            }

            // The remaining fields are signatureCounter, logTime and signatureValue — but some
            // log kinds (audit) put an extra OCTET STRING (seAuditData) between the
            // signatureAlgorithm and the counter. So scan to the end rather than reading
            // positionally: the two integers are the counter then logTime (when unixTime is
            // used), a UTCTime/GeneralizedTime supplies logTime for string-time devices, and the
            // LAST octet string is the real signature. Any other octet string (audit's
            // seAuditData) is simply passed over.
            var logTime: Long? = null
            var signatureHex: String? = null
            val trailingInts = ArrayList<Long>()
            while (offset < data.size) {
                val tag = data[offset].toInt() and 0xFF
                val lenRes = readLength(data, offset + 1)
                val start = lenRes.second
                val end = (start + lenRes.first).coerceAtMost(data.size)
                when (tag) {
                    TAG_INTEGER -> trailingInts.add(data.toUnsignedLong(start, end))
                    TAG_OCTET_STRING -> signatureHex = data.sliceArray(start until end).toHexString()
                    TAG_UTC_TIME, TAG_GENERALIZED_TIME ->
                        if (logTime == null) {
                            logTime = parseAsn1TimeToEpoch(String(data, start, end - start, Charsets.US_ASCII), tag)
                        }
                }
                offset = end
            }
            val signatureCounter: Long? = trailingInts.getOrNull(0)
            if (logTime == null) logTime = trailingInts.getOrNull(1)

            TseDto.ParsedLogMessageDto(
                version = version.first,
                operationType = operationType,
                clientId = clientId,
                processType = processType,
                transactionNumber = transactionNumber,
                signatureCounter = signatureCounter,
                logTime = logTime,
                serialNumberHex = serialNumberHex,
                signatureHex = signatureHex,
                signatureAlgorithm = signatureAlgorithm,
                eventOrigin = eventOrigin,
                eventTriggeredByUser = eventTriggeredByUser,
                eventDataHex = eventDataHex,
                authenticationResult = authenticationResult,
                remainingRetries = remainingRetries,
                authRole = authRole,
            )
        } catch (e: Exception) {
            logger.debug("Failed to parse TR-03153 log message: ${e.message}")
            null
        }
    }

    // ========================================
    // TLV Parsing Helpers
    // ========================================

    /**
     * Read ASN.1 length encoding.
     * @return Pair(length value, new offset after length bytes)
     */
    private fun readLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= data.size) return Pair(0, offset)

        val first = data[offset].toInt() and 0xFF
        if (first < 0x80) {
            return Pair(first, offset + 1)
        }

        val numBytes = first and 0x7F
        var length = 0
        for (i in 0 until numBytes) {
            if (offset + 1 + i >= data.size) return Pair(0, data.size)
            length = (length shl 8) or (data[offset + 1 + i].toInt() and 0xFF)
        }
        return Pair(length, offset + 1 + numBytes)
    }

    /**
     * Read INTEGER value if tag matches.
     * @return Pair(integer value, new offset) or null
     */
    private fun readIntegerIfTag(data: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= data.size) return null
        if (data[offset].toInt() and 0xFF != TAG_INTEGER) return null

        val lenResult = readLength(data, offset + 1)
        val length = lenResult.first
        var pos = lenResult.second

        var value = 0
        // Handle sign extension for first byte
        if (length > 0 && pos < data.size) {
            value = data[pos].toInt() // Signed
            pos++
        }
        for (i in 1 until length) {
            if (pos >= data.size) break
            value = (value shl 8) or (data[pos].toInt() and 0xFF)
            pos++
        }

        return Pair(value, pos)
    }

    /**
     * Read OID if tag matches.
     * @return Pair(OID string, new offset) or null
     */
    private fun readOidIfTag(data: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset >= data.size) return null
        if (data[offset].toInt() and 0xFF != TAG_OID) return null

        val lenResult = readLength(data, offset + 1)
        val length = lenResult.first
        val start = lenResult.second
        val end = (start + length).coerceAtMost(data.size)

        // Encode OID bytes as dotted notation
        val oidBytes = data.sliceArray(start until end)
        val oid = encodeOid(oidBytes)

        return Pair(oid, end)
    }

    /**
     * Read OCTET STRING if tag matches.
     * @return Pair(byte array, new offset) or null
     */
    private fun readOctetStringIfTag(data: ByteArray, offset: Int): Pair<ByteArray, Int>? {
        if (offset >= data.size) return null
        if (data[offset].toInt() and 0xFF != TAG_OCTET_STRING) return null

        val lenResult = readLength(data, offset + 1)
        val length = lenResult.first
        val start = lenResult.second
        val end = (start + length).coerceAtMost(data.size)

        return Pair(data.sliceArray(start until end), end)
    }

    /** Big-endian unsigned value of the bytes in [start, end). A leading DER 0x00 contributes 0. */
    private fun ByteArray.toUnsignedLong(start: Int = 0, end: Int = size): Long {
        var v = 0L
        for (i in start until end) v = (v shl 8) or (this[i].toLong() and 0xFF)
        return v
    }

    /**
     * Decode the eventData SEQUENCE of an authentication system log into
     * (authenticationResult, remainingRetries, role).
     *
     * The two auth events lay their fields out differently:
     *  - authenticateUser (TR-03151-1 §2.2): { userId PrintableString, role PrintableString,
     *    authenticationResult PinAuthenticationResult(ENUMERATED), remainingRetries INTEGER }
     *  - authenticateSmaAdmin (TR-03153-1 §9.8.1): { role PrintableString OPTIONAL,
     *    authenticationResult BOOLEAN, remainingRetries INTEGER OPTIONAL }
     * The element tags are distinct, so decode by tag; role is the FIRST string for
     * authenticateSmaAdmin but the SECOND (after userId) for authenticateUser.
     */
    private fun decodeAuthEventData(content: ByteArray, eventType: String): Triple<String?, Long?, String?> {
        var authResult: String? = null
        var remaining: Long? = null
        val strings = ArrayList<String>()
        var o = 0
        while (o < content.size) {
            val tag = content[o].toInt() and 0xFF
            val lenRes = readLength(content, o + 1)
            val start = lenRes.second
            val end = (start + lenRes.first).coerceAtMost(content.size)
            when (tag) {
                TAG_PRINTABLE_STRING, TAG_UTF8_STRING -> strings.add(content.sliceArray(start until end).toDecodedString())
                TAG_BOOLEAN -> authResult = if (end > start && content[start].toInt() != 0) "success" else "failure"
                TAG_ENUMERATED -> authResult = pinAuthResultName(content.toUnsignedLong(start, end))
                TAG_INTEGER -> remaining = content.toUnsignedLong(start, end)
            }
            o = end
        }
        val role = if (eventType == "authenticateUser") strings.getOrNull(1) else strings.getOrNull(0)
        return Triple(authResult, remaining, role)
    }

    /** Map PinAuthenticationResult ENUMERATED values (TR-03151-1 §2.2) to their names. */
    private fun pinAuthResultName(v: Long): String = when (v) {
        0L -> "success"
        1L -> "unknownUserId"
        2L -> "incorrectPin"
        3L -> "pinBlocked"
        else -> "unknown($v)"
    }

    /** Decode content bytes as a text field (PrintableString / UTF8String), trimmed of NULs. */
    private fun ByteArray.toDecodedString(): String = String(this, Charsets.UTF_8).trim(' ')

    /**
     * Parse an ASN.1 UTCTime / GeneralizedTime string to unix epoch seconds, or null.
     *
     * The TSEs seen so far use unixTime (an INTEGER), so this is the fallback for devices
     * configured with a string time format. Both forms here are UTC (trailing 'Z').
     */
    private fun parseAsn1TimeToEpoch(text: String, tag: Int): Long? = try {
        val s = text.trim().removeSuffix("Z")
        val (year, rest) = if (tag == TAG_UTC_TIME) {
            val yy = s.substring(0, 2).toInt()
            (if (yy >= 50) 1900 + yy else 2000 + yy) to s.substring(2) // YYMMDD…
        } else {
            s.substring(0, 4).toInt() to s.substring(4) // YYYYMMDD…
        }
        java.time.LocalDateTime.of(
            year,
            rest.substring(0, 2).toInt(),
            rest.substring(2, 4).toInt(),
            rest.substring(4, 6).toInt(),
            if (rest.length >= 8) rest.substring(6, 8).toInt() else 0,
            if (rest.length >= 10) rest.substring(8, 10).toInt() else 0,
        ).toEpochSecond(java.time.ZoneOffset.UTC)
    } catch (_: Exception) {
        null
    }

    /**
     * Encode OID bytes to dotted notation string.
     */
    private fun encodeOid(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val components = mutableListOf<Long>()

        // First byte encodes first two components
        val first = bytes[0].toInt() and 0xFF
        components.add((first / 40).toLong())
        components.add((first % 40).toLong())

        // Remaining bytes use variable-length encoding
        var value = 0L
        for (i in 1 until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            value = (value shl 7) or (b.toLong() and 0x7F)
            if (b and 0x80 == 0) {
                components.add(value)
                value = 0
            }
        }

        return components.joinToString(".")
    }

    /**
     * Convert byte array to hex string.
     */
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
