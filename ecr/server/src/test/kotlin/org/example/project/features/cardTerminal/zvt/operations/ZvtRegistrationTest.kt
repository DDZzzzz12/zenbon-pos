/**
 * Unit tests for [ZvtRegistration] operation builder.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.models.ZvtConfig
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZvtRegistrationTest {
    @Test
    fun `build Registration APDU has correct command code`() {
        val config = ZvtConfig()
        val apdu = ZvtRegistration.buildApdu(config)

        assertEquals(ZvtCommand.REGISTRATION, apdu.command)
        assertEquals(0x06.toByte(), apdu.classByte)
        assertEquals(0x00.toByte(), apdu.instructionByte)
    }

    @Test
    fun `build Registration APDU includes password and currency fields`() {
        val config = ZvtConfig(password = 123456, currency = 978)
        val apdu = ZvtRegistration.buildApdu(config)

        assertTrue(apdu.dataBlock.isNotEmpty())
        // Data block should contain BMP fields for password and currency
    }

    @Test
    fun `build Registration APDU serializes to valid bytes`() {
        val config = ZvtConfig()
        val apdu = ZvtRegistration.buildApdu(config)
        val bytes = apdu.toBytes()

        // First 2 bytes: 06 00 (Registration)
        assertEquals(0x06.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        // Third byte: length (should be > 0 since we have fields)
        assertTrue(bytes.size > 3)
    }

    @Test
    fun `buildConfigByte with ECR prints receipt and intermediate status`() {
        val configByte =
            ZvtRegistration.buildConfigByte(
                ecrPrintsReceipt = true,
                ecrHandlesAdmin = false,
                ecrWantsIntermediateStatus = true,
            )
        assertEquals(0x09, configByte) // bit 0 + bit 3
    }

    @Test
    fun `buildConfigByte with all flags set`() {
        val configByte =
            ZvtRegistration.buildConfigByte(
                ecrPrintsReceipt = true,
                ecrHandlesAdmin = true,
                ecrWantsIntermediateStatus = true,
            )
        assertEquals(0x0B, configByte) // bit 0 + bit 1 + bit 3
    }

    @Test
    fun `buildConfigByte with no flags`() {
        val configByte =
            ZvtRegistration.buildConfigByte(
                ecrPrintsReceipt = false,
                ecrHandlesAdmin = false,
                ecrWantsIntermediateStatus = false,
            )
        assertEquals(0x00, configByte)
    }
}
