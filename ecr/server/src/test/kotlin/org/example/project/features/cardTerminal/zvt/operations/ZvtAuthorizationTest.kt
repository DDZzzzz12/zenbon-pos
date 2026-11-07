/**
 * Unit tests for [ZvtAuthorization] operation builder.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtBmpField
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZvtAuthorizationTest {
    @Test
    fun `build Authorization APDU has correct command code`() {
        val apdu = ZvtAuthorization.buildApdu(1000)
        assertEquals(ZvtCommand.AUTHORIZATION, apdu.command)
        assertEquals(0x06.toByte(), apdu.classByte)
        assertEquals(0x01.toByte(), apdu.instructionByte)
    }

    @Test
    fun `build Authorization APDU includes amount field`() {
        val apdu = ZvtAuthorization.buildApdu(5000, 978)
        val fields = apdu.bmpFields
        val amount = ZvtBmpField.getAmount(fields)
        assertEquals(5000L, amount)
    }

    @Test
    fun `build Authorization APDU includes currency code`() {
        val apdu = ZvtAuthorization.buildApdu(1000, 978)
        val fields = apdu.bmpFields
        val currency = ZvtBmpField.getCurrencyCode(fields)
        assertEquals(978, currency)
    }

    @Test
    fun `Authorization with zero amount throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtAuthorization.buildApdu(0)
        }
    }

    @Test
    fun `Authorization with negative amount throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            ZvtAuthorization.buildApdu(-100)
        }
    }

    @Test
    fun `Authorization APDU serializes to valid bytes`() {
        val apdu = ZvtAuthorization.buildApdu(12345, 978)
        val bytes = apdu.toBytes()

        // First 2 bytes: 06 01
        assertEquals(0x06.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        // Should have data block
        assertTrue(bytes.size > 3)
    }

    @Test
    fun `Authorization with USD currency code`() {
        val apdu = ZvtAuthorization.buildApdu(1000, 840)
        val fields = apdu.bmpFields
        assertEquals(840, ZvtBmpField.getCurrencyCode(fields))
    }
}
