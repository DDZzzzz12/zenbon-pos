/**
 * APDU and TLV length field encoding/decoding for the ZVT protocol.
 *
 * ZVT uses two different length encoding schemes:
 * 1. **APDU length** — Standard (1 byte: 0x00-0xFE) or Extended (0xFF + 2 bytes big-endian)
 * 2. **TLV BER length** — Single byte (0-127) or multi-byte (0x81+1 byte, 0x82+2 bytes)
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import java.io.ByteArrayOutputStream

/**
 * Utility for encoding and decoding length fields in ZVT APDU and TLV containers.
 *
 * Handles both APDU-level length encoding (standard/extended) and
 * BER-TLV length encoding used within TLV containers (BMP field 06).
 */
object ZvtLengthEncoding {
    // ===== APDU Length Encoding =====

    /**
     * Encodes an APDU data block length.
     *
     * - Length 0-254: encoded as 1 byte
     * - Length 255-65535: encoded as 0xFF followed by 2 bytes (big-endian)
     *
     * @param length The data block length to encode
     * @return Encoded length bytes (1 or 3 bytes)
     * @throws IllegalArgumentException if length is negative or exceeds 65535
     */
    fun encodeApduLength(length: Int): ByteArray {
        require(length in 0..65535) { "APDU length must be 0-65535, got: $length" }
        return if (length <= 254) {
            byteArrayOf(length.toByte())
        } else {
            byteArrayOf(
                0xFF.toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            )
        }
    }

    /**
     * Decodes an APDU length from a byte array starting at the given offset.
     *
     * @param data The byte array containing the length field
     * @param offset The starting position in the array
     * @return Pair of (decoded length, number of bytes consumed)
     * @throws IllegalArgumentException if data is too short
     */
    fun decodeApduLength(
        data: ByteArray,
        offset: Int = 0,
    ): Pair<Int, Int> {
        require(offset < data.size) { "Offset $offset exceeds data size ${data.size}" }
        val firstByte = data[offset].toInt() and 0xFF
        return if (firstByte != 0xFF) {
            Pair(firstByte, 1)
        } else {
            require(offset + 2 < data.size) { "Extended APDU length requires 3 bytes, but only ${data.size - offset} available" }
            val highByte = data[offset + 1].toInt() and 0xFF
            val lowByte = data[offset + 2].toInt() and 0xFF
            val length = (highByte shl 8) or lowByte
            Pair(length, 3)
        }
    }

    // ===== TLV BER Length Encoding =====

    /**
     * Encodes a TLV length using BER (Basic Encoding Rules) format.
     *
     * - Length 0-127: single byte
     * - Length 128-255: 0x81 followed by 1 byte
     * - Length 256-65535: 0x82 followed by 2 bytes (big-endian)
     *
     * @param length The TLV value length to encode
     * @return BER-encoded length bytes (1-3 bytes)
     * @throws IllegalArgumentException if length is negative or exceeds 65535
     */
    fun encodeTlvLength(length: Int): ByteArray {
        require(length in 0..65535) { "TLV length must be 0-65535, got: $length" }
        return when {
            length <= 127 -> byteArrayOf(length.toByte())
            length <= 255 -> byteArrayOf(0x81.toByte(), length.toByte())
            else ->
                byteArrayOf(
                    0x82.toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte(),
                )
        }
    }

    /**
     * Decodes a BER-TLV length from a byte array starting at the given offset.
     *
     * @param data The byte array containing the BER length field
     * @param offset The starting position in the array
     * @return Pair of (decoded length, number of bytes consumed)
     * @throws IllegalArgumentException if data is too short or encoding is invalid
     */
    fun decodeTlvLength(
        data: ByteArray,
        offset: Int = 0,
    ): Pair<Int, Int> {
        require(offset < data.size) { "Offset $offset exceeds data size ${data.size}" }
        val firstByte = data[offset].toInt() and 0xFF
        return when {
            firstByte <= 127 -> Pair(firstByte, 1)
            firstByte == 0x81 -> {
                require(offset + 1 < data.size) { "BER length 0x81 requires 2 bytes total" }
                val length = data[offset + 1].toInt() and 0xFF
                Pair(length, 2)
            }
            firstByte == 0x82 -> {
                require(offset + 2 < data.size) { "BER length 0x82 requires 3 bytes total" }
                val highByte = data[offset + 1].toInt() and 0xFF
                val lowByte = data[offset + 2].toInt() and 0xFF
                Pair((highByte shl 8) or lowByte, 3)
            }
            else -> throw IllegalArgumentException(
                "Unsupported BER length encoding: 0x${firstByte.toString(16).padStart(2, '0')}",
            )
        }
    }

    /**
     * Encodes a variable-length LL-Var field (2-digit length prefix in BCD).
     *
     * LL-Var fields use a 1-byte BCD length prefix (00-99) followed by the data.
     *
     * @param data The variable-length data
     * @return LL-Var encoded byte array (1-byte BCD length + data)
     * @throws IllegalArgumentException if data exceeds 99 bytes
     */
    fun encodeLLVar(data: ByteArray): ByteArray {
        require(data.size <= 99) { "LL-Var data must be 0-99 bytes, got: ${data.size}" }
        val out = ByteArrayOutputStream()
        out.write(ZvtBcdCodec.encode(data.size.toLong(), 1))
        out.write(data)
        return out.toByteArray()
    }

    /**
     * Decodes a variable-length LL-Var field.
     *
     * @param data The byte array containing the LL-Var encoded data
     * @param offset The starting position
     * @return Pair of (decoded data bytes, total bytes consumed including length prefix)
     */
    fun decodeLLVar(
        data: ByteArray,
        offset: Int = 0,
    ): Pair<ByteArray, Int> {
        require(offset < data.size) { "Offset $offset exceeds data size ${data.size}" }
        val length = ZvtBcdCodec.decode(byteArrayOf(data[offset])).toInt()
        require(offset + 1 + length <= data.size) {
            "LL-Var declares length $length but only ${data.size - offset - 1} bytes available"
        }
        val value = data.copyOfRange(offset + 1, offset + 1 + length)
        return Pair(value, 1 + length)
    }

    /**
     * Encodes a variable-length LLL-Var field (3-digit length prefix in BCD).
     *
     * LLL-Var fields use a 2-byte BCD length prefix (0000-9999) followed by the data.
     *
     * @param data The variable-length data
     * @return LLL-Var encoded byte array (2-byte BCD length + data)
     * @throws IllegalArgumentException if data exceeds 999 bytes
     */
    fun encodeLLLVar(data: ByteArray): ByteArray {
        require(data.size <= 999) { "LLL-Var data must be 0-999 bytes, got: ${data.size}" }
        val out = ByteArrayOutputStream()
        out.write(ZvtBcdCodec.encode(data.size.toLong(), 2))
        out.write(data)
        return out.toByteArray()
    }

    /**
     * Decodes a variable-length LLL-Var field.
     *
     * @param data The byte array containing the LLL-Var encoded data
     * @param offset The starting position
     * @return Pair of (decoded data bytes, total bytes consumed including length prefix)
     */
    fun decodeLLLVar(
        data: ByteArray,
        offset: Int = 0,
    ): Pair<ByteArray, Int> {
        require(offset + 1 < data.size) { "LLL-Var needs at least 2 bytes for length prefix" }
        val length = ZvtBcdCodec.decode(data.copyOfRange(offset, offset + 2)).toInt()
        require(offset + 2 + length <= data.size) {
            "LLL-Var declares length $length but only ${data.size - offset - 2} bytes available"
        }
        val value = data.copyOfRange(offset + 2, offset + 2 + length)
        return Pair(value, 2 + length)
    }
}
