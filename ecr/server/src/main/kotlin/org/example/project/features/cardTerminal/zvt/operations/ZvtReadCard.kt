/**
 * ZVT Read Card (06 C0) operation builder.
 *
 * Reads card data from the payment terminal without initiating a
 * payment transaction. Useful for customer identification, loyalty
 * programs, and card validation.
 *
 * Reference: PA00P015 Chapter 2, command 06 C0.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.BmpFieldDefinition
import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Read Card (06 C0) command APDU.
 *
 * The Read Card command optionally includes:
 * - Timeout (BMP 01): How long to wait for a card (1 byte, in seconds)
 */
object ZvtReadCard {
    /**
     * Builds a Read Card APDU.
     *
     * @param timeoutSeconds Time to wait for card insertion (default: 30 seconds)
     * @return The Read Card command APDU
     */
    fun buildApdu(timeoutSeconds: Int = 30): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.TIMEOUT.fieldId] = byteArrayOf(timeoutSeconds.toByte())
        return ZvtApdu.create(ZvtCommand.READ_CARD, fields)
    }
}
