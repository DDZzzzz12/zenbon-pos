/**
 * ZVT Log-Off (06 02) operation builder.
 *
 * Log-Off ends the ECR↔PT session. After Log-Off, the terminal
 * returns to its idle state and the TCP connection should be closed.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Log-Off (06 02) command APDU.
 */
object ZvtLogOff {
    /**
     * Builds a Log-Off APDU.
     *
     * @return The Log-Off command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.LOG_OFF)
}
