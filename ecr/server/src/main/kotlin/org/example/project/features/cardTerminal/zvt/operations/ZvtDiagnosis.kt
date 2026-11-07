/**
 * ZVT Diagnosis (06 70) operation builder.
 *
 * Diagnosis triggers a self-test on the terminal, checking hardware
 * components and communication links.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Diagnosis (06 70) command APDU.
 */
object ZvtDiagnosis {
    /**
     * Builds a Diagnosis APDU.
     *
     * @return The Diagnosis command APDU
     */
    fun buildApdu(): ZvtApdu = ZvtApdu.create(ZvtCommand.DIAGNOSIS)
}
