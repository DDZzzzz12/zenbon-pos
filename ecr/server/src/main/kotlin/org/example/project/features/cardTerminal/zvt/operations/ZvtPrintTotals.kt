/**
 * ZVT Print Totals (06 12) operation builder.
 *
 * Print Totals requests the terminal to print a totals report
 * showing all transactions since the last End-of-Day settlement.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the Print Totals (06 12) command APDU.
 */
object ZvtPrintTotals {
    /**
     * Builds a Print Totals APDU.
     *
     * @param password Terminal password (optional)
     * @return The Print Totals command APDU
     */
    fun buildApdu(password: Int = 0): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(password)
        return ZvtApdu.create(ZvtCommand.PRINT_TOTALS, fields)
    }
}
