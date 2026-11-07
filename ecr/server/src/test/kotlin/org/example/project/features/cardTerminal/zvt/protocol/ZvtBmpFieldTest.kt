/**
 * Unit tests for [ZvtBmpField] BMP field parsing and building.
 *
 * Verifies correct serialization/deserialization of BMP field maps
 * and convenience extraction methods.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZvtBmpFieldTest {
    @Test
    fun `build and parse data block with amount field`() {
        val fields =
            mapOf(
                BmpFieldDefinition.AMOUNT.fieldId to ZvtBcdCodec.encodeAmount(5000),
            )
        val dataBlock = ZvtBmpField.buildDataBlock(fields)

        // Should be: 04 (field ID) + 00 00 00 00 50 00 (6-byte BCD)
        assertEquals(7, dataBlock.size)
        assertEquals(0x04, dataBlock[0].toInt() and 0xFF)

        val parsed = ZvtBmpField.parseDataBlock(dataBlock)
        assertEquals(5000L, ZvtBmpField.getAmount(parsed))
    }

    @Test
    fun `build and parse data block with multiple fields`() {
        val fields =
            mapOf(
                BmpFieldDefinition.AMOUNT.fieldId to ZvtBcdCodec.encodeAmount(1234),
                BmpFieldDefinition.CURRENCY_CODE.fieldId to ZvtBcdCodec.encodeCurrency(978),
                BmpFieldDefinition.RESULT_CODE.fieldId to byteArrayOf(0x00),
            )
        val dataBlock = ZvtBmpField.buildDataBlock(fields)
        val parsed = ZvtBmpField.parseDataBlock(dataBlock)

        assertEquals(1234L, ZvtBmpField.getAmount(parsed))
        assertEquals(978, ZvtBmpField.getCurrencyCode(parsed))
        assertEquals(0x00, ZvtBmpField.getResultCode(parsed))
    }

    @Test
    fun `getAmount returns null when field not present`() {
        val fields = emptyMap<Int, ByteArray>()
        assertNull(ZvtBmpField.getAmount(fields))
    }

    @Test
    fun `getResultCode extracts result code`() {
        val fields =
            mapOf(
                BmpFieldDefinition.RESULT_CODE.fieldId to byteArrayOf(0x64.toByte()),
            )
        assertEquals(0x64, ZvtBmpField.getResultCode(fields))
    }

    @Test
    fun `getReceiptNumber extracts BCD receipt number`() {
        val fields =
            mapOf(
                BmpFieldDefinition.RECEIPT_NUMBER.fieldId to ZvtBcdCodec.encode(42, 2),
            )
        assertEquals(42, ZvtBmpField.getReceiptNumber(fields))
    }

    @Test
    fun `getCardType extracts card type byte`() {
        val fields =
            mapOf(
                BmpFieldDefinition.CARD_TYPE.fieldId to byteArrayOf(0x05),
            )
        assertEquals(5, ZvtBmpField.getCardType(fields))
    }

    @Test
    fun `getTerminalId extracts BCD terminal ID`() {
        val fields =
            mapOf(
                BmpFieldDefinition.TERMINAL_ID.fieldId to byteArrayOf(0x12, 0x34, 0x56, 0x78),
            )
        assertEquals("12345678", ZvtBmpField.getTerminalId(fields))
    }

    @Test
    fun `fields are serialized in ascending order`() {
        // Currency (0x49) should come after Amount (0x04)
        val fields =
            mapOf(
                BmpFieldDefinition.CURRENCY_CODE.fieldId to ZvtBcdCodec.encodeCurrency(978),
                BmpFieldDefinition.AMOUNT.fieldId to ZvtBcdCodec.encodeAmount(100),
            )
        val dataBlock = ZvtBmpField.buildDataBlock(fields)

        // First byte should be 0x04 (Amount), not 0x49 (Currency)
        assertEquals(0x04, dataBlock[0].toInt() and 0xFF)
    }

    @Test
    fun `parse empty data block returns empty map`() {
        val parsed = ZvtBmpField.parseDataBlock(ByteArray(0))
        assertEquals(0, parsed.size)
    }

    @Test
    fun `getTraceNumber extracts trace number`() {
        val fields =
            mapOf(
                BmpFieldDefinition.TRACE_NUMBER.fieldId to ZvtBcdCodec.encodeTraceNumber(1234),
            )
        assertEquals(1234, ZvtBmpField.getTraceNumber(fields))
    }
}
