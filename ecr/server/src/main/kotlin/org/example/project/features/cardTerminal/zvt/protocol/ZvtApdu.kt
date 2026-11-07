/**
 * ZVT APDU (Application Protocol Data Unit) assembly and disassembly.
 *
 * An APDU is the fundamental message unit in the ZVT protocol.
 * Structure: [Control Field: 2 bytes] [Length: 1-3 bytes] [Data Block: variable]
 *
 * The control field identifies the command (class byte + instruction byte).
 * The length field uses standard (1 byte) or extended (0xFF + 2 bytes) encoding.
 * The data block contains BMP fields and optional TLV containers.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import java.io.ByteArrayOutputStream

/**
 * Represents a parsed ZVT APDU message.
 *
 * @property command The identified ZVT command (null if unknown)
 * @property classByte The raw class byte from the control field
 * @property instructionByte The raw instruction byte from the control field
 * @property dataBlock The raw data block bytes (may be empty)
 * @property bmpFields Parsed BMP fields from the data block (lazily parsed)
 */
data class ZvtApdu(
    val command: ZvtCommand?,
    val classByte: Byte,
    val instructionByte: Byte,
    val dataBlock: ByteArray,
) {
    /**
     * Lazily parsed BMP fields from the data block.
     */
    val bmpFields: Map<Int, ByteArray> by lazy {
        if (dataBlock.isEmpty()) {
            emptyMap()
        } else {
            ZvtBmpField.parseDataBlock(dataBlock)
        }
    }

    /**
     * Whether this APDU is a positive acknowledgement (ACK).
     */
    val isAck: Boolean
        get() = ZvtCommand.isAck(classByte, instructionByte)

    /**
     * Whether this APDU is a negative acknowledgement (NAK).
     */
    val isNak: Boolean
        get() = ZvtCommand.isNak(classByte)

    /**
     * The NAK error code, if this is a NAK message.
     * Returns null for non-NAK messages.
     */
    val nakErrorCode: Int?
        get() = if (isNak) ZvtCommand.getNakErrorCode(instructionByte) else null

    /**
     * Whether this APDU signals completion (returns master-rights to ECR).
     */
    val isCompletion: Boolean
        get() = command == ZvtCommand.COMPLETION

    /**
     * Whether this APDU is an abort from the PT side.
     */
    val isPtAbort: Boolean
        get() = command == ZvtCommand.PT_ABORT

    /**
     * Whether this APDU is a status information message.
     */
    val isStatusInformation: Boolean
        get() = command == ZvtCommand.STATUS_INFORMATION

    /**
     * Whether this APDU is an intermediate status update.
     */
    val isIntermediateStatus: Boolean
        get() = command == ZvtCommand.INTERMEDIATE_STATUS

    /**
     * Whether this APDU is a print line request.
     */
    val isPrintLine: Boolean
        get() = command == ZvtCommand.PRINT_LINE

    /**
     * Whether this APDU is a print text block request.
     */
    val isPrintTextBlock: Boolean
        get() = command == ZvtCommand.PRINT_TEXT_BLOCK

    /**
     * Serializes this APDU into a byte array for transmission.
     *
     * @return The complete APDU byte array
     */
    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        // Control field (2 bytes)
        out.write(classByte.toInt())
        out.write(instructionByte.toInt())
        // Length field (1 or 3 bytes)
        out.write(ZvtLengthEncoding.encodeApduLength(dataBlock.size))
        // Data block
        if (dataBlock.isNotEmpty()) {
            out.write(dataBlock)
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ZvtApdu) return false
        return classByte == other.classByte &&
            instructionByte == other.instructionByte &&
            dataBlock.contentEquals(other.dataBlock)
    }

    override fun hashCode(): Int {
        var result = classByte.toInt()
        result = 31 * result + instructionByte
        result = 31 * result + dataBlock.contentHashCode()
        return result
    }

    override fun toString(): String {
        val cmdName = command?.description ?: "Unknown"
        val classHex = (classByte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        val instrHex = (instructionByte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        return "APDU($cmdName [$classHex $instrHex], dataLen=${dataBlock.size})"
    }

    companion object {
        /**
         * Creates an ACK (positive acknowledgement) APDU.
         *
         * ACK is always: 80 00 00 (control field + zero length)
         *
         * @return An ACK APDU
         */
        fun ack(): ZvtApdu =
            ZvtApdu(
                command = ZvtCommand.ACK,
                classByte = 0x80.toByte(),
                instructionByte = 0x00,
                dataBlock = ByteArray(0),
            )

        /**
         * Creates an APDU from a command and BMP field map.
         *
         * @param command The ZVT command
         * @param fields Optional BMP fields to include in the data block
         * @return The constructed APDU
         */
        fun create(
            command: ZvtCommand,
            fields: Map<Int, ByteArray> = emptyMap(),
        ): ZvtApdu {
            val dataBlock =
                if (fields.isEmpty()) {
                    ByteArray(0)
                } else {
                    ZvtBmpField.buildDataBlock(fields)
                }

            return ZvtApdu(
                command = command,
                classByte = command.classByte,
                instructionByte = command.instructionByte,
                dataBlock = dataBlock,
            )
        }

        /**
         * Creates an APDU from a command with a raw data block.
         *
         * @param command The ZVT command
         * @param dataBlock The raw data block bytes
         * @return The constructed APDU
         */
        fun createWithRawData(
            command: ZvtCommand,
            dataBlock: ByteArray,
        ): ZvtApdu =
            ZvtApdu(
                command = command,
                classByte = command.classByte,
                instructionByte = command.instructionByte,
                dataBlock = dataBlock,
            )

        /**
         * Parses a complete APDU from a byte array.
         *
         * Reads the control field (2 bytes), length field (1-3 bytes),
         * and data block from the input.
         *
         * @param data The raw byte array containing the APDU
         * @param offset Starting offset in the array
         * @return Pair of (parsed APDU, total bytes consumed)
         * @throws IllegalArgumentException if data is too short or malformed
         */
        fun parse(
            data: ByteArray,
            offset: Int = 0,
        ): Pair<ZvtApdu, Int> {
            require(data.size - offset >= 3) { "APDU requires at least 3 bytes (control + length)" }

            // Parse control field
            val classByte = data[offset]
            val instructionByte = data[offset + 1]

            // Parse length
            val (dataLength, lengthBytes) = ZvtLengthEncoding.decodeApduLength(data, offset + 2)

            val headerSize = 2 + lengthBytes
            require(data.size - offset >= headerSize + dataLength) {
                "APDU declares data length $dataLength but only ${data.size - offset - headerSize} bytes available"
            }

            // Extract data block
            val dataBlock =
                if (dataLength > 0) {
                    data.copyOfRange(offset + headerSize, offset + headerSize + dataLength)
                } else {
                    ByteArray(0)
                }

            val command = ZvtCommand.fromBytes(classByte, instructionByte)
            val apdu = ZvtApdu(command, classByte, instructionByte, dataBlock)

            return Pair(apdu, headerSize + dataLength)
        }
    }
}
