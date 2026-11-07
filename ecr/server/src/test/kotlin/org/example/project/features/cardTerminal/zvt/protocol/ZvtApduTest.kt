/**
 * Unit tests for [ZvtApdu] construction and parsing.
 *
 * Verifies correct APDU assembly, serialization, and parsing
 * including control fields, length encoding, and data blocks.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZvtApduTest {
    @Test
    fun `ACK APDU is 80 00 00`() {
        val ack = ZvtApdu.ack()
        val bytes = ack.toBytes()
        assertEquals(3, bytes.size)
        assertBytesEqual(byteArrayOf(0x80.toByte(), 0x00, 0x00), bytes)
    }

    @Test
    fun `parse ACK APDU`() {
        val data = byteArrayOf(0x80.toByte(), 0x00, 0x00)
        val (apdu, consumed) = ZvtApdu.parse(data)
        assertEquals(3, consumed)
        assertTrue(apdu.isAck)
        assertEquals(ZvtCommand.ACK, apdu.command)
    }

    @Test
    fun `create Registration APDU with fields`() {
        val fields =
            mapOf(
                BmpFieldDefinition.PASSWORD.fieldId to ZvtBcdCodec.encodePassword(0),
                BmpFieldDefinition.CURRENCY_CODE.fieldId to ZvtBcdCodec.encodeCurrency(978),
            )
        val apdu = ZvtApdu.create(ZvtCommand.REGISTRATION, fields)
        assertEquals(ZvtCommand.REGISTRATION, apdu.command)
        assertEquals(0x06.toByte(), apdu.classByte)
        assertEquals(0x00.toByte(), apdu.instructionByte)
        assertTrue(apdu.dataBlock.isNotEmpty())
    }

    @Test
    fun `serialize and reparse APDU roundtrip`() {
        val fields =
            mapOf(
                BmpFieldDefinition.AMOUNT.fieldId to ZvtBcdCodec.encodeAmount(1000),
            )
        val original = ZvtApdu.create(ZvtCommand.AUTHORIZATION, fields)
        val bytes = original.toBytes()
        val (parsed, _) = ZvtApdu.parse(bytes)

        assertEquals(original.classByte, parsed.classByte)
        assertEquals(original.instructionByte, parsed.instructionByte)
        assertTrue(original.dataBlock.contentEquals(parsed.dataBlock))
    }

    @Test
    fun `parse NAK APDU`() {
        val data = byteArrayOf(0x84.toByte(), 0x9A.toByte(), 0x00)
        val (apdu, _) = ZvtApdu.parse(data)
        assertTrue(apdu.isNak)
        assertEquals(ZvtCommand.NAK, apdu.command)
        assertEquals(0x9A, apdu.nakErrorCode)
    }

    @Test
    fun `parse Completion APDU`() {
        val data = byteArrayOf(0x06, 0x0F, 0x00)
        val (apdu, _) = ZvtApdu.parse(data)
        assertTrue(apdu.isCompletion)
        assertEquals(ZvtCommand.COMPLETION, apdu.command)
    }

    @Test
    fun `parse Status Information APDU with data`() {
        // 04 0F + length + result code field (27 00)
        val data = byteArrayOf(0x04, 0x0F, 0x02, 0x27, 0x00)
        val (apdu, consumed) = ZvtApdu.parse(data)
        assertEquals(5, consumed)
        assertTrue(apdu.isStatusInformation)
        assertEquals(2, apdu.dataBlock.size)
    }

    @Test
    fun `parse Intermediate Status APDU`() {
        // 04 FF + length 01 + status byte 06 (please wait)
        val data = byteArrayOf(0x04, 0xFF.toByte(), 0x01, 0x06)
        val (apdu, _) = ZvtApdu.parse(data)
        assertTrue(apdu.isIntermediateStatus)
        assertEquals(1, apdu.dataBlock.size)
        assertEquals(0x06, apdu.dataBlock[0].toInt() and 0xFF)
    }

    @Test
    fun `APDU with empty data block`() {
        val apdu = ZvtApdu.create(ZvtCommand.ABORT)
        val bytes = apdu.toBytes()
        // 06 B0 00 (command + zero-length data)
        assertEquals(3, bytes.size)
        assertEquals(0x06.toByte(), bytes[0])
        assertEquals(0xB0.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
    }

    @Test
    fun `command lookup for all defined commands`() {
        for (cmd in ZvtCommand.entries) {
            if (cmd == ZvtCommand.NAK) continue // NAK has special lookup
            val found = ZvtCommand.fromBytes(cmd.classByte, cmd.instructionByte)
            assertNotNull(found, "Command ${cmd.name} should be found by fromBytes")
            assertEquals(cmd, found)
        }
    }

    @Test
    fun `toString produces readable output`() {
        val apdu = ZvtApdu.create(ZvtCommand.AUTHORIZATION)
        val str = apdu.toString()
        assertTrue(str.contains("Authorization"))
        assertTrue(str.contains("06 01"))
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
