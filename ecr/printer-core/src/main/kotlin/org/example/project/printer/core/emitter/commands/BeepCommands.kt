/**
 * ESC/POS beep/buzzer commands.
 *
 * Supports ESC B (configurable beep) with BEL fallback for
 * printers that don't support the extended command.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

import org.example.project.core.domain.enums.setup.BeepMode

/**
 * Beep and buzzer commands for ESC/POS printers.
 */
object BeepCommands {
    /**
     * ESC B n m — beep n times, each m × 100ms duration.
     *
     * @param mode Beep mode (SHORT = 200ms, LONG = 800ms, DISABLED = no-op)
     * @return ESC/POS byte sequence
     */
    fun beep(mode: BeepMode): ByteArray =
        when (mode) {
            BeepMode.SHORT -> byteArrayOf(0x1B, 0x42, 0x01, 0x02)
            BeepMode.LONG -> byteArrayOf(0x1B, 0x42, 0x01, 0x08)
            BeepMode.DISABLED -> byteArrayOf()
        }

    /**
     * BEL (0x07) — universal fallback bell command.
     *
     * Single byte, no duration control. Used as fallback for
     * printers that ignore ESC B.
     *
     * @return BEL byte
     */
    fun bel(): ByteArray = byteArrayOf(0x07)
}
