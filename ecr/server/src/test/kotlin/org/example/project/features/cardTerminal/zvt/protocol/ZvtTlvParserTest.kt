/**
 * Unit tests for [ZvtTlvParser] and [ZvtTlvBuilder] TLV handling.
 *
 * Verifies correct parsing of TLV containers with single-byte tags,
 * multi-byte tags, constructed (nested) tags, and BER length encoding.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZvtTlvParserTest {
    @Test
    fun `parse single primitive TLV entry`() {
        // Tag 0x04, Length 0x02, Value 0x01 0x02
        val data = byteArrayOf(0x04, 0x02, 0x01, 0x02)
        val entries = ZvtTlvParser.parse(data)
        assertEquals(1, entries.size)
        assertEquals("04", entries[0].tagHex)
        assertEquals(2, entries[0].value.size)
        assertFalse(entries[0].isConstructed)
    }

    @Test
    fun `parse multiple TLV entries`() {
        // Entry 1: Tag 0x04, Len 1, Val 0xFF
        // Entry 2: Tag 0x27, Len 1, Val 0x00
        val data = byteArrayOf(0x04, 0x01, 0xFF.toByte(), 0x27, 0x01, 0x00)
        val entries = ZvtTlvParser.parse(data)
        assertEquals(2, entries.size)
        assertEquals("04", entries[0].tagHex)
        assertEquals("27", entries[1].tagHex)
    }

    @Test
    fun `parse multi-byte tag`() {
        // Two-byte tag: 0x1F 0x04, Len 2, Val 0xAB 0xCD
        val data = byteArrayOf(0x1F, 0x04, 0x02, 0xAB.toByte(), 0xCD.toByte())
        val entries = ZvtTlvParser.parse(data)
        assertEquals(1, entries.size)
        assertEquals("1F04", entries[0].tagHex)
        assertEquals(2, entries[0].value.size)
    }

    @Test
    fun `parse constructed tag with children`() {
        // Constructed tag 0x20 (bit 6 set), Len 4
        // Child: Tag 0x01, Len 1, Val 0xFF
        val childData = byteArrayOf(0x01, 0x01, 0xFF.toByte())
        val data = byteArrayOf(0x20, childData.size.toByte()) + childData
        val entries = ZvtTlvParser.parse(data)
        assertEquals(1, entries.size)
        assertTrue(entries[0].isConstructed)

        val children = entries[0].children()
        assertEquals(1, children.size)
        assertEquals("01", children[0].tagHex)
    }

    @Test
    fun `build and parse TLV roundtrip`() {
        val builder =
            ZvtTlvBuilder()
                .addTag(0x04, byteArrayOf(0x01, 0x02))
                .addTag(0x27, byteArrayOf(0x00))

        val built = builder.build()
        val parsed = ZvtTlvParser.parse(built)

        assertEquals(2, parsed.size)
        assertEquals("04", parsed[0].tagHex)
        assertEquals("27", parsed[1].tagHex)
    }

    @Test
    fun `build constructed TLV with children`() {
        val builder =
            ZvtTlvBuilder()
                .addConstructed(0x21) {
                    addTag(0x01.toByte(), byteArrayOf(0xAA.toByte()))
                    addTag(0x02.toByte(), byteArrayOf(0xBB.toByte()))
                }

        val built = builder.build()
        val parsed = ZvtTlvParser.parse(built)

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].isConstructed)
        val children = parsed[0].children()
        assertEquals(2, children.size)
    }

    @Test
    fun `findByTag locates nested entry`() {
        val builder =
            ZvtTlvBuilder()
                .addTag(0x04, byteArrayOf(0x01))
                .addConstructed(0x21) {
                    addTag(0x42, byteArrayOf(0xCC.toByte()))
                }

        val entries = ZvtTlvParser.parse(builder.build())
        val found = ZvtTlvParser.findByTag(entries, "42")
        assertEquals(0xCC.toByte(), found?.value?.get(0))
    }

    @Test
    fun `toMap flattens TLV entries`() {
        val builder =
            ZvtTlvBuilder()
                .addTag(0x04, byteArrayOf(0x01))
                .addTag(0x27, byteArrayOf(0x00))

        val entries = ZvtTlvParser.parse(builder.build())
        val map = ZvtTlvParser.toMap(entries)

        assertEquals(2, map.size)
        assertTrue(map.containsKey("04"))
        assertTrue(map.containsKey("27"))
    }

    @Test
    fun `builder size returns entry count`() {
        val builder =
            ZvtTlvBuilder()
                .addTag(0x01.toByte(), byteArrayOf(0x00))
                .addTag(0x02.toByte(), byteArrayOf(0x00))
        assertEquals(2, builder.size())
    }

    @Test
    fun `BER length encoding 127 uses single byte`() {
        val encoded = ZvtLengthEncoding.encodeTlvLength(127)
        assertEquals(1, encoded.size)
        assertEquals(127, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun `BER length encoding 128 uses two bytes`() {
        val encoded = ZvtLengthEncoding.encodeTlvLength(128)
        assertEquals(2, encoded.size)
        assertEquals(0x81, encoded[0].toInt() and 0xFF)
        assertEquals(128, encoded[1].toInt() and 0xFF)
    }

    @Test
    fun `BER length encoding 256 uses three bytes`() {
        val encoded = ZvtLengthEncoding.encodeTlvLength(256)
        assertEquals(3, encoded.size)
        assertEquals(0x82, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun `BER length roundtrip`() {
        for (length in listOf(0, 1, 127, 128, 255, 256, 1000, 65535)) {
            val encoded = ZvtLengthEncoding.encodeTlvLength(length)
            val (decoded, _) = ZvtLengthEncoding.decodeTlvLength(encoded)
            assertEquals(length, decoded, "Roundtrip failed for length $length")
        }
    }

    @Test
    fun `fromMap creates TLV container`() {
        val map =
            mapOf(
                0x04 to byteArrayOf(0x01),
                0x27 to byteArrayOf(0x00),
            )
        val built = ZvtTlvBuilder.fromMap(map)
        val parsed = ZvtTlvParser.parse(built)
        assertEquals(2, parsed.size)
    }

    @Test
    fun `skip padding bytes 0x00`() {
        // Padding byte 0x00 should be skipped
        val data = byteArrayOf(0x00, 0x04, 0x01, 0xFF.toByte())
        val entries = ZvtTlvParser.parse(data)
        assertEquals(1, entries.size)
        assertEquals("04", entries[0].tagHex)
    }
}
