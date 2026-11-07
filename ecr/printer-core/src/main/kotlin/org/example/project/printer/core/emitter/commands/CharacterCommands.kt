/**
 * ESC/POS character formatting commands.
 *
 * Controls bold, underline, and font size modes.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

import org.example.project.printer.core.enums.FontSize

/**
 * Character formatting commands for text styling.
 */
object CharacterCommands {
    /** Enable bold text (ESC E 1). */
    val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)

    /** Disable bold text (ESC E 0). */
    val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)

    /** Enable underline text (ESC - 1). */
    val UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01)

    /** Disable underline text (ESC - 0). */
    val UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)

    /**
     * Set font size using the type-safe [FontSize] enum.
     *
     * @param size Desired font size
     * @return ESC/POS byte sequence
     */
    fun setFontSize(size: FontSize): ByteArray = size.bytes
}
