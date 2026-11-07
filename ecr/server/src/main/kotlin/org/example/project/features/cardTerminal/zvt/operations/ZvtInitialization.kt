/**
 * ZVT Initialization (06 93) operation builder.
 *
 * Forces the terminal to perform a network initialization (re-registration
 * with the acquirer/host). This is used when the terminal needs to refresh
 * its configuration from the payment processor network.
 *
 * Reference: PA00P015 Chapter 2, command 06 93.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Initialization (06 93) command APDU.
 *
 * The Initialization command typically has no data block. The terminal
 * will perform a network re-initialization and respond with Completion
 * when finished.
 */
object ZvtInitialization {
    /**
     * Builds an Initialization APDU.
     *
     * @return The Initialization command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.INITIALIZATION)
}
