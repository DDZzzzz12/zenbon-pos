/**
 * ZVT Refund (06 31) operation builder.
 *
 * Refund issues a credit back to the customer's card.
 * Requires the refund amount (BMP 04) and terminal password (BMP 3D).
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the Refund (06 31) command APDU.
 */
object ZvtRefund {
    /**
     * Builds a Refund APDU with the specified amount.
     *
     * @param amountCents Refund amount in minor currency units
     * @param password Terminal password (optional)
     * @return The Refund command APDU
     */
    fun buildApdu(
        amountCents: Long,
        password: Int = 0,
    ): ZvtApdu {
        require(amountCents > 0) { "Refund amount must be positive" }

        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(password)

        return ZvtApdu.create(ZvtCommand.REFUND, fields)
    }
}
