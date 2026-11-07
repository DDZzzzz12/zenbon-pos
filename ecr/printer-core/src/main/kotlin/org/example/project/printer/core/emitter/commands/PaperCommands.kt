/**
 * ESC/POS paper control commands.
 *
 * Handles paper cutting and cash drawer operations.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

import org.example.project.printer.core.enums.CashDrawerPin
import org.example.project.printer.core.enums.CutType

/**
 * Paper and cash drawer commands for ESC/POS printers.
 */
object PaperCommands {
    /**
     * Cut paper using the specified cut type.
     *
     * @param type Full or partial cut
     * @return ESC/POS byte sequence
     */
    fun cut(type: CutType): ByteArray = type.bytes

    /**
     * Open the cash drawer connected to the specified pin.
     *
     * @param pin Cash drawer pin (PIN_2 or PIN_5)
     * @return ESC/POS byte sequence
     */
    fun openCashDrawer(pin: CashDrawerPin): ByteArray = pin.bytes
}
