/**
 * ZVT End-of-Day (06 50) operation builder.
 *
 * End-of-Day triggers daily settlement / reconciliation with the
 * payment processor. The terminal totals all transactions from the
 * current business day and submits them for settlement.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the End-of-Day (06 50) command APDU.
 */
object ZvtEndOfDay {
    /**
     * Builds an End-of-Day APDU.
     *
     * @param password Terminal password (optional)
     * @return The End-of-Day command APDU
     */
    fun buildApdu(password: Int = 0): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(password)

        return ZvtApdu.create(ZvtCommand.END_OF_DAY, fields)
    }
}
