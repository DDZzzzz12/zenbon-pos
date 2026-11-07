/**
 * Unit tests for [ZvtBcdCodec] BCD encoding/decoding.
 *
 * Verifies correct encoding and decoding of amounts, trace numbers,
 * passwords, currency codes, dates, and times in BCD format.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZvtBcdCodecTest {
    @Test
    fun `encode amount 12345 cents to 6-byte BCD`() {
        val encoded = ZvtBcdCodec.encodeAmount(12345)
        // 12345 → 00 00 00 01 23 45
        assertEquals(6, encoded.size)
        assertBytesEqual(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x23, 0x45), encoded)
    }

    @Test
    fun `decode 6-byte BCD amount to 12345 cents`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x23, 0x45)
        assertEquals(12345L, ZvtBcdCodec.decodeAmount(data))
    }

    @Test
    fun `encode and decode zero amount`() {
        val encoded = ZvtBcdCodec.encodeAmount(0)
        assertBytesEqual(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encoded)
        assertEquals(0L, ZvtBcdCodec.decodeAmount(encoded))
    }

    @Test
    fun `encode and decode large amount 999999999999`() {
        val encoded = ZvtBcdCodec.encodeAmount(999999999999)
        assertBytesEqual(byteArrayOf(0x99.toByte(), 0x99.toByte(), 0x99.toByte(), 0x99.toByte(), 0x99.toByte(), 0x99.toByte()), encoded)
        assertEquals(999999999999L, ZvtBcdCodec.decodeAmount(encoded))
    }

    @Test
    fun `encode EUR currency code 978 to 2-byte BCD`() {
        val encoded = ZvtBcdCodec.encodeCurrency(978)
        assertBytesEqual(byteArrayOf(0x09, 0x78), encoded)
    }

    @Test
    fun `decode 2-byte BCD currency code to 978`() {
        val data = byteArrayOf(0x09, 0x78)
        assertEquals(978, ZvtBcdCodec.decodeCurrency(data))
    }

    @Test
    fun `encode trace number 123456`() {
        val encoded = ZvtBcdCodec.encodeTraceNumber(123456)
        assertBytesEqual(byteArrayOf(0x12, 0x34, 0x56), encoded)
    }

    @Test
    fun `decode trace number`() {
        val data = byteArrayOf(0x12, 0x34, 0x56)
        assertEquals(123456, ZvtBcdCodec.decodeTraceNumber(data))
    }

    @Test
    fun `encode password 12345`() {
        val encoded = ZvtBcdCodec.encodePassword(12345)
        assertBytesEqual(byteArrayOf(0x01, 0x23, 0x45), encoded)
    }

    @Test
    fun `encode time 14 30 59`() {
        val encoded = ZvtBcdCodec.encodeTime(14, 30, 59)
        assertBytesEqual(byteArrayOf(0x14, 0x30, 0x59), encoded)
    }

    @Test
    fun `decode time`() {
        val data = byteArrayOf(0x14, 0x30, 0x59)
        val (h, m, s) = ZvtBcdCodec.decodeTime(data)
        assertEquals(14, h)
        assertEquals(30, m)
        assertEquals(59, s)
    }

    @Test
    fun `encode date month 12 day 25`() {
        val encoded = ZvtBcdCodec.encodeDate(12, 25)
        assertBytesEqual(byteArrayOf(0x12, 0x25), encoded)
    }

    @Test
    fun `decode date`() {
        val data = byteArrayOf(0x12, 0x25)
        val (month, day) = ZvtBcdCodec.decodeDate(data)
        assertEquals(12, month)
        assertEquals(25, day)
    }

    @Test
    fun `encode digit string`() {
        val encoded = ZvtBcdCodec.encodeDigitString("1234")
        assertBytesEqual(byteArrayOf(0x12, 0x34), encoded)
    }

    @Test
    fun `encode odd-length digit string with left padding`() {
        val encoded = ZvtBcdCodec.encodeDigitString("123")
        assertBytesEqual(byteArrayOf(0x01, 0x23), encoded)
    }

    @Test
    fun `decode digit string`() {
        val data = byteArrayOf(0x12, 0x34)
        assertEquals("1234", ZvtBcdCodec.decodeDigitString(data))
    }

    @Test
    fun `negative amount throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtBcdCodec.encodeAmount(-1)
        }
    }

    @Test
    fun `invalid BCD nibble throws exception`() {
        // 0xAB has invalid BCD nibbles (A=10, B=11)
        assertFailsWith<IllegalArgumentException> {
            ZvtBcdCodec.decode(byteArrayOf(0xAB.toByte()))
        }
    }

    @Test
    fun `wrong size for amount decode throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtBcdCodec.decodeAmount(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun `invalid hour throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtBcdCodec.encodeTime(25, 0, 0)
        }
    }

    @Test
    fun `invalid month throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtBcdCodec.encodeDate(13, 1)
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
