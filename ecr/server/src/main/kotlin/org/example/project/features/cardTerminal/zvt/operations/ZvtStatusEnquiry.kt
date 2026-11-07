/**
 * ZVT Status-Enquiry (05 01) operation builder.
 *
 * Status-Enquiry polls the terminal for its current status and
 * can be used as a health check / keep-alive mechanism.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Status-Enquiry (05 01) command APDU.
 */
object ZvtStatusEnquiry {
    /**
     * Builds a Status-Enquiry APDU.
     *
     * The Status-Enquiry command has no data block.
     *
     * @return The Status-Enquiry command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.STATUS_ENQUIRY)
}
