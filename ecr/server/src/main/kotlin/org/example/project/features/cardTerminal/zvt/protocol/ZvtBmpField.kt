/**
 * BMP (Bitmap) field parser and builder for ZVT protocol data blocks.
 *
 * ZVT data blocks consist of BMP fields identified by their field number.
 * Each field has a defined type and length (fixed, LL-Var, or LLL-Var).
 * Fields are serialized in ascending order by field number in the data block.
 *
 * Field numbers referenced from PA00P015 Chapter 13.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import java.io.ByteArrayOutputStream

/**
 * Defines the known BMP field types in the ZVT protocol.
 *
 * Each field has a number (hex), encoding type, and fixed byte length
 * where applicable. Variable-length fields use LL-Var or LLL-Var encoding.
 *
 * @property fieldId The BMP field identifier byte
 * @property lengthType How the field length is determined
 * @property fixedLength Fixed byte count for FIXED-type fields, null otherwise
 * @property description Human-readable field description
 */
enum class BmpFieldDefinition(
    val fieldId: Int,
    val lengthType: LengthType,
    val fixedLength: Int? = null,
    val description: String,
) {
    /** Timeout value (1 byte) */
    TIMEOUT(0x01, LengthType.FIXED, 1, "Timeout"),

    /** Maximum number of status information (1 byte) */
    MAX_STATUS_INFOS(0x02, LengthType.FIXED, 1, "Max Status Infos"),

    /** Service byte / result code response (1 byte) */
    SERVICE_BYTE(0x03, LengthType.FIXED, 1, "Service Byte"),

    /** Amount in minor currency units (6 bytes BCD) */
    AMOUNT(0x04, LengthType.FIXED, 6, "Amount"),

    /** Pump number (1 byte) */
    PUMP_NR(0x05, LengthType.FIXED, 1, "Pump Number"),

    /** TLV container (variable length, LLL-Var) */
    TLV_CONTAINER(0x06, LengthType.LLL_VAR, null, "TLV Container"),

    /** Trace number (3 bytes BCD) */
    TRACE_NUMBER(0x0B, LengthType.FIXED, 3, "Trace Number"),

    /** Time HHMMSS (3 bytes BCD) */
    TIME(0x0C, LengthType.FIXED, 3, "Time"),

    /** Date MMDD (2 bytes BCD) */
    DATE(0x0D, LengthType.FIXED, 2, "Date"),

    /** Expiry date YYMM (2 bytes BCD) */
    EXPIRY_DATE(0x0E, LengthType.FIXED, 2, "Expiry Date"),

    /** Card sequence number (2 bytes BCD) */
    CARD_SEQUENCE_NUMBER(0x17, LengthType.FIXED, 2, "Card Sequence Number"),

    /** Payment type (1 byte) */
    PAYMENT_TYPE(0x19, LengthType.FIXED, 1, "Payment Type"),

    /** Card number / PAN (LL-Var, BCD-encoded) */
    PAN(0x22, LengthType.LL_VAR, null, "PAN / Card Number"),

    /** Card technology identification (1 byte) */
    CARD_TECHNOLOGY(0x23, LengthType.FIXED, 1, "Card Technology"),

    /** Result code (1 byte) */
    RESULT_CODE(0x27, LengthType.FIXED, 1, "Result Code"),

    /** Terminal ID (4 bytes BCD) */
    TERMINAL_ID(0x29, LengthType.FIXED, 4, "Terminal ID"),

    /** Amount original (6 bytes BCD) */
    AMOUNT_ORIGINAL(0x2D, LengthType.FIXED, 6, "Amount Original"),

    /** Authorization attribute / AID (LL-Var) */
    AID(0x2E, LengthType.LL_VAR, null, "Authorization Attribute / AID"),

    /** VU (Vertrags-Unternehmer) number (LL-Var) */
    VU_NUMBER(0x2A, LengthType.LL_VAR, null, "VU Number"),

    /** Password (3 bytes BCD) */
    PASSWORD(0x3D, LengthType.FIXED, 3, "Password"),

    /** Currency code (2 bytes BCD, ISO 4217) */
    CURRENCY_CODE(0x49, LengthType.FIXED, 2, "Currency Code"),

    /** Additional text / receipt line (LL-Var) */
    ADDITIONAL_TEXT(0x3C, LengthType.LL_VAR, null, "Additional Text"),

    /** Status byte / AS terminal ID (1 byte) */
    STATUS_BYTE(0x60, LengthType.FIXED, 1, "Status Byte"),

    /** Receipt number (2 bytes BCD) */
    RECEIPT_NUMBER(0x87.toInt(), LengthType.FIXED, 2, "Receipt Number"),

    /** Card type (1 byte) */
    CARD_TYPE(0x8A.toInt(), LengthType.FIXED, 1, "Card Type"),

    /** Card name (LL-Var) */
    CARD_NAME(0x8B.toInt(), LengthType.LL_VAR, null, "Card Name"),

    /** Additional data / turnover records (LLL-Var) */
    ADDITIONAL_DATA(0x8C.toInt(), LengthType.LLL_VAR, null, "Additional Data"),

    /** Blocked goods groups bitmap (LL-Var) */
    BLOCKED_GOODS_GROUPS(0x2C, LengthType.LL_VAR, null, "Blocked Goods Groups"),

    /** Turnover record / totals data (LLL-Var) */
    TURNOVER_RECORD(0x88.toInt(), LengthType.LLL_VAR, null, "Turnover Record"),

    /** Display duration in seconds (1 byte) */
    DISPLAY_DURATION(0xE0.toInt(), LengthType.FIXED, 1, "Display Duration"),

    /** Number of beeps (1 byte) */
    BEEPS(0xE1.toInt(), LengthType.FIXED, 1, "Number of Beeps"),

    /** Minimum input length (1 byte) */
    INPUT_MIN_LENGTH(0xE2.toInt(), LengthType.FIXED, 1, "Input Minimum Length"),

    /** Maximum input length (1 byte) */
    INPUT_MAX_LENGTH(0xE3.toInt(), LengthType.FIXED, 1, "Input Maximum Length"),

    /** Echo input character (1 byte) */
    INPUT_ECHO_CHAR(0xE4.toInt(), LengthType.FIXED, 1, "Input Echo Character"),

    /** Display text line 1 (LL-Var) */
    DISPLAY_LINE_1(0xF1.toInt(), LengthType.LL_VAR, null, "Display Line 1"),

    /** Display text line 2 (LL-Var) */
    DISPLAY_LINE_2(0xF2.toInt(), LengthType.LL_VAR, null, "Display Line 2"),

    /** Display text line 3 (LL-Var) */
    DISPLAY_LINE_3(0xF3.toInt(), LengthType.LL_VAR, null, "Display Line 3"),

    /** Display text line 4 (LL-Var) */
    DISPLAY_LINE_4(0xF4.toInt(), LengthType.LL_VAR, null, "Display Line 4"),

    /** Display text line 5 (LL-Var) */
    DISPLAY_LINE_5(0xF5.toInt(), LengthType.LL_VAR, null, "Display Line 5"),

    /** Display text line 6 (LL-Var) */
    DISPLAY_LINE_6(0xF6.toInt(), LengthType.LL_VAR, null, "Display Line 6"),

    /** Display text line 7 (LL-Var) */
    DISPLAY_LINE_7(0xF7.toInt(), LengthType.LL_VAR, null, "Display Line 7"),

    /** Display text line 8 (LL-Var) */
    DISPLAY_LINE_8(0xF8.toInt(), LengthType.LL_VAR, null, "Display Line 8"),
    ;

    /**
     * How the field length is determined in the wire format.
     */
    enum class LengthType {
        /** Fixed-length field */
        FIXED,

        /** 2-digit BCD length prefix (1 byte, max 99) */
        LL_VAR,

        /** 4-digit BCD length prefix (2 bytes, max 999) */
        LLL_VAR,
    }

    companion object {
        private val byId = entries.associateBy { it.fieldId }

        /**
         * Looks up a BMP field definition by its field ID.
         *
         * @param fieldId The BMP field identifier byte
         * @return The field definition, or null if unknown
         */
        fun fromId(fieldId: Int): BmpFieldDefinition? = byId[fieldId]
    }
}

/**
 * Parser and builder for BMP field data blocks in ZVT APDUs.
 *
 * BMP fields appear in the data block portion of an APDU, sorted by
 * ascending field number. This object handles serialization and
 * deserialization of field maps.
 */
object ZvtBmpField {
    /**
     * Builds a data block from a map of BMP field values.
     *
     * Fields are written in ascending order of their field ID.
     * Each field is preceded by its 1-byte field ID, followed by
     * the field data (with length prefix for variable-length fields).
     *
     * @param fields Map of field ID to raw byte value
     * @return Serialized data block byte array
     */
    fun buildDataBlock(fields: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((fieldId, data) in fields.toSortedMap()) {
            out.write(fieldId)
            val definition = BmpFieldDefinition.fromId(fieldId)
            if (definition != null) {
                when (definition.lengthType) {
                    BmpFieldDefinition.LengthType.FIXED -> {
                        out.write(data)
                    }
                    BmpFieldDefinition.LengthType.LL_VAR -> {
                        out.write(ZvtLengthEncoding.encodeLLVar(data))
                    }
                    BmpFieldDefinition.LengthType.LLL_VAR -> {
                        out.write(ZvtLengthEncoding.encodeLLLVar(data))
                    }
                }
            } else {
                // Unknown field — treat as raw (fixed-length, data is written as-is)
                out.write(data)
            }
        }
        return out.toByteArray()
    }

    /**
     * Parses BMP fields from a data block byte array.
     *
     * Reads fields sequentially from the data block. Each field starts
     * with its 1-byte ID followed by its data (length determined by
     * the field definition's length type).
     *
     * Unknown fields are skipped with a warning logged, as their
     * length cannot be determined reliably.
     *
     * @param data The data block byte array
     * @param offset Starting offset in the data array
     * @param length Number of bytes in the data block
     * @return Map of field ID to raw byte value
     */
    fun parseDataBlock(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Map<Int, ByteArray> {
        val fields = mutableMapOf<Int, ByteArray>()
        var pos = offset
        val end = offset + length

        while (pos < end) {
            val fieldId = data[pos].toInt() and 0xFF
            pos++

            val definition = BmpFieldDefinition.fromId(fieldId)
            if (definition == null) {
                // Unknown BMP field — cannot determine length, stop parsing
                break
            }

            when (definition.lengthType) {
                BmpFieldDefinition.LengthType.FIXED -> {
                    val fixedLen = definition.fixedLength!!
                    if (pos + fixedLen > end) break
                    fields[fieldId] = data.copyOfRange(pos, pos + fixedLen)
                    pos += fixedLen
                }
                BmpFieldDefinition.LengthType.LL_VAR -> {
                    if (pos >= end) break
                    val (value, consumed) = ZvtLengthEncoding.decodeLLVar(data, pos)
                    fields[fieldId] = value
                    pos += consumed
                }
                BmpFieldDefinition.LengthType.LLL_VAR -> {
                    if (pos + 1 >= end) break
                    val (value, consumed) = ZvtLengthEncoding.decodeLLLVar(data, pos)
                    fields[fieldId] = value
                    pos += consumed
                }
            }
        }

        return fields
    }

    /**
     * Convenience method to extract the amount from BMP field 04.
     *
     * @param fields Parsed BMP field map
     * @return Amount in minor currency units, or null if field not present
     */
    fun getAmount(fields: Map<Int, ByteArray>): Long? {
        val data = fields[BmpFieldDefinition.AMOUNT.fieldId] ?: return null
        return ZvtBcdCodec.decodeAmount(data)
    }

    /**
     * Convenience method to extract the result code from BMP field 27.
     *
     * @param fields Parsed BMP field map
     * @return Result code byte value, or null if field not present
     */
    fun getResultCode(fields: Map<Int, ByteArray>): Int? {
        val data = fields[BmpFieldDefinition.RESULT_CODE.fieldId] ?: return null
        return data[0].toInt() and 0xFF
    }

    /**
     * Convenience method to extract the receipt number from BMP field 87.
     *
     * @param fields Parsed BMP field map
     * @return Receipt number, or null if field not present
     */
    fun getReceiptNumber(fields: Map<Int, ByteArray>): Int? {
        val data = fields[BmpFieldDefinition.RECEIPT_NUMBER.fieldId] ?: return null
        return ZvtBcdCodec.decode(data).toInt()
    }

    /**
     * Convenience method to extract the trace number from BMP field 0B.
     *
     * @param fields Parsed BMP field map
     * @return Trace number, or null if field not present
     */
    fun getTraceNumber(fields: Map<Int, ByteArray>): Int? {
        val data = fields[BmpFieldDefinition.TRACE_NUMBER.fieldId] ?: return null
        return ZvtBcdCodec.decodeTraceNumber(data)
    }

    /**
     * Convenience method to extract the card type from BMP field 8A.
     *
     * @param fields Parsed BMP field map
     * @return Card type byte value, or null if field not present
     */
    fun getCardType(fields: Map<Int, ByteArray>): Int? {
        val data = fields[BmpFieldDefinition.CARD_TYPE.fieldId] ?: return null
        return data[0].toInt() and 0xFF
    }

    /**
     * Convenience method to extract the terminal ID from BMP field 29.
     *
     * @param fields Parsed BMP field map
     * @return Terminal ID as digit string, or null if field not present
     */
    fun getTerminalId(fields: Map<Int, ByteArray>): String? {
        val data = fields[BmpFieldDefinition.TERMINAL_ID.fieldId] ?: return null
        return ZvtBcdCodec.decodeDigitString(data)
    }

    /**
     * Convenience method to extract the currency code from BMP field 49.
     *
     * @param fields Parsed BMP field map
     * @return ISO 4217 currency code, or null if field not present
     */
    fun getCurrencyCode(fields: Map<Int, ByteArray>): Int? {
        val data = fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] ?: return null
        return ZvtBcdCodec.decodeCurrency(data)
    }

    /**
     * Convenience method to extract the PAN / card number from BMP field 22.
     *
     * @param fields Parsed BMP field map
     * @return PAN as digit string, or null if field not present
     */
    fun getPan(fields: Map<Int, ByteArray>): String? {
        val data = fields[BmpFieldDefinition.PAN.fieldId] ?: return null
        return ZvtBcdCodec.decodeDigitString(data)
    }
}
