/**
 * Unit tests for [ZvtLengthEncoding] APDU and TLV length encoding.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZvtLengthEncodingTest {
    // ===== APDU Length Tests =====

    @Test
    fun `APDU length 0 encodes as single byte`() {
        val encoded = ZvtLengthEncoding.encodeApduLength(0)
        assertEquals(1, encoded.size)
        assertEquals(0x00, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun `APDU length 254 encodes as single byte`() {
        val encoded = ZvtLengthEncoding.encodeApduLength(254)
        assertEquals(1, encoded.size)
        assertEquals(254, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun `APDU length 255 encodes as extended (3 bytes)`() {
        val encoded = ZvtLengthEncoding.encodeApduLength(255)
        assertEquals(3, encoded.size)
        assertEquals(0xFF, encoded[0].toInt() and 0xFF)
        assertEquals(0x00, encoded[1].toInt() and 0xFF)
        assertEquals(0xFF, encoded[2].toInt() and 0xFF)
    }

    @Test
    fun `APDU length 1000 encodes correctly`() {
        val encoded = ZvtLengthEncoding.encodeApduLength(1000)
        assertEquals(3, encoded.size)
        assertEquals(0xFF, encoded[0].toInt() and 0xFF)
        assertEquals(0x03, encoded[1].toInt() and 0xFF) // 1000 >> 8 = 3
        assertEquals(0xE8, encoded[2].toInt() and 0xFF) // 1000 & 0xFF = 232
    }

    @Test
    fun `APDU length roundtrip for standard lengths`() {
        for (length in listOf(0, 1, 100, 254)) {
            val encoded = ZvtLengthEncoding.encodeApduLength(length)
            val (decoded, bytesConsumed) = ZvtLengthEncoding.decodeApduLength(encoded)
            assertEquals(length, decoded)
            assertEquals(1, bytesConsumed)
        }
    }

    @Test
    fun `APDU length roundtrip for extended lengths`() {
        for (length in listOf(255, 500, 1000, 65535)) {
            val encoded = ZvtLengthEncoding.encodeApduLength(length)
            val (decoded, bytesConsumed) = ZvtLengthEncoding.decodeApduLength(encoded)
            assertEquals(length, decoded)
            assertEquals(3, bytesConsumed)
        }
    }

    @Test
    fun `APDU length negative throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtLengthEncoding.encodeApduLength(-1)
        }
    }

    @Test
    fun `APDU length exceeding 65535 throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtLengthEncoding.encodeApduLength(65536)
        }
    }

    // ===== LL-Var Tests =====

    @Test
    fun `LL-Var encode and decode roundtrip`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = ZvtLengthEncoding.encodeLLVar(data)
        val (decoded, consumed) = ZvtLengthEncoding.decodeLLVar(encoded)
        assertEquals(3, decoded.size)
        assertEquals(4, consumed) // 1 byte length + 3 bytes data
        assertBytesEqual(data, decoded)
    }

    @Test
    fun `LL-Var with empty data`() {
        val data = ByteArray(0)
        val encoded = ZvtLengthEncoding.encodeLLVar(data)
        val (decoded, consumed) = ZvtLengthEncoding.decodeLLVar(encoded)
        assertEquals(0, decoded.size)
        assertEquals(1, consumed) // 1 byte length only
    }

    // ===== LLL-Var Tests =====

    @Test
    fun `LLL-Var encode and decode roundtrip`() {
        val data = ByteArray(150) { it.toByte() }
        val encoded = ZvtLengthEncoding.encodeLLLVar(data)
        val (decoded, consumed) = ZvtLengthEncoding.decodeLLLVar(encoded)
        assertEquals(150, decoded.size)
        assertEquals(152, consumed) // 2 bytes length + 150 bytes data
    }

    @Test
    fun `LLL-Var exceeding 999 bytes throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtLengthEncoding.encodeLLLVar(ByteArray(1000))
        }
    }

    private fun assertBytesEqual(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(
            expected.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
            actual.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }
}
