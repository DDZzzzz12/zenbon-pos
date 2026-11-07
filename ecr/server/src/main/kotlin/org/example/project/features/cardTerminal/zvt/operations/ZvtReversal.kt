/**
 * ZVT Reversal (06 30) operation builder.
 *
 * Reversal cancels a previously completed transaction. The original
 * transaction is identified by its receipt number (BMP 87).
 * A password (BMP 3D) may be required by the terminal.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the Reversal (06 30) command APDU.
 */
object ZvtReversal {
    /**
     * Builds a Reversal APDU for the given receipt number.
     *
     * @param receiptNumber Receipt number of the transaction to reverse
     * @param password Terminal password (optional, 0 if not required)
     * @return The Reversal command APDU
     */
    fun buildApdu(
        receiptNumber: Int,
        password: Int = 0,
    ): ZvtApdu {
        require(receiptNumber > 0) { "Receipt number must be positive" }

        val fields = mutableMapOf<Int, ByteArray>()
        // Receipt number (BMP 87)
        fields[BmpFieldDefinition.RECEIPT_NUMBER.fieldId] = ZvtBcdCodec.encode(receiptNumber.toLong(), 2)
        // Password (BMP 3D)
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(password)

        return ZvtApdu.create(ZvtCommand.REVERSAL, fields)
    }
}
