/**
 * ZVT Send Turnover Totals (0F 10) operation builder.
 *
 * Sends the ECR's accumulated turnover totals to the payment terminal.
 * This is used during end-of-day reconciliation to compare the ECR's
 * transaction records with the terminal's records.
 *
 * Reference: PA00P015 Chapter 2, command 0F 10.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.BmpFieldDefinition
import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtBcdCodec
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Send Turnover Totals (0F 10) command APDU.
 *
 * The turnover totals command includes:
 * - Password (BMP 3D): Terminal password
 * - Turnover record data (BMP 88): Turnover data block (LLL-Var)
 */
object ZvtSendTurnoverTotals {
    /**
     * Builds a Send Turnover Totals APDU.
     *
     * @param password Terminal password
     * @param turnoverData Raw turnover record data block
     * @return The Send Turnover Totals command APDU
     */
    fun buildApdu(
        password: Int,
        turnoverData: ByteArray,
    ): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(password)
        fields[BmpFieldDefinition.TURNOVER_RECORD.fieldId] = turnoverData
        return ZvtApdu.create(ZvtCommand.SEND_TURNOVER_TOTALS, fields)
    }
}
