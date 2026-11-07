/**
 * ZVT Abort (06 B0) operation builder.
 *
 * Abort cancels the current operation on the terminal. The terminal
 * may or may not honor the abort depending on its current state.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Abort (06 B0) command APDU.
 */
object ZvtAbort {
    /**
     * Builds an Abort APDU.
     *
     * @return The Abort command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.ABORT)
}
