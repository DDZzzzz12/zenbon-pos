/**
 * ZVT Reset Terminal (06 18) operation builder.
 *
 * Resets the payment terminal. The terminal will perform a hardware
 * reset and may need to be re-registered afterwards.
 *
 * Reference: PA00P015 Chapter 2, command 06 18.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Reset Terminal (06 18) command APDU.
 *
 * The Reset Terminal command has no data block — it sends only
 * the 2-byte control field with a zero-length body.
 */
object ZvtResetTerminal {
    /**
     * Builds a Reset Terminal APDU.
     *
     * @return The Reset Terminal command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.RESET_TERMINAL)
}
