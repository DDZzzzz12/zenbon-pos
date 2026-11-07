/**
 * ZVT Display Text (06 E0) operation builder.
 *
 * Displays text on the payment terminal screen. Supports up to 8 lines
 * of text, a display duration, and optional beep count.
 *
 * Reference: PA00P015 Chapter 2, command 06 E0.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.BmpFieldDefinition
import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Display Text (06 E0) command APDU.
 *
 * The Display Text command uses BMP fields:
 * - F1-F8: Text lines 1-8 (LL-Var, max ~32 chars per line)
 * - E0: Display duration in seconds (1 byte)
 * - E1: Number of beeps (1 byte)
 */
object ZvtDisplayText {
    /**
     * Builds a Display Text APDU with the specified lines and options.
     *
     * @param lines List of text lines (max 8). Empty strings are skipped.
     * @param durationSeconds How long to show the text (0 = until keypress, default: 0)
     * @param beeps Number of beeps (default: 0)
     * @return The Display Text command APDU
     */
    fun buildApdu(
        lines: List<String>,
        durationSeconds: Int = 0,
        beeps: Int = 0,
    ): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()

        // Display duration (BMP E0)
        fields[BmpFieldDefinition.DISPLAY_DURATION.fieldId] = byteArrayOf(durationSeconds.toByte())

        // Beeps (BMP E1)
        if (beeps > 0) {
            fields[BmpFieldDefinition.BEEPS.fieldId] = byteArrayOf(beeps.toByte())
        }

        // Text lines F1-F8
        val lineFields =
            listOf(
                BmpFieldDefinition.DISPLAY_LINE_1,
                BmpFieldDefinition.DISPLAY_LINE_2,
                BmpFieldDefinition.DISPLAY_LINE_3,
                BmpFieldDefinition.DISPLAY_LINE_4,
                BmpFieldDefinition.DISPLAY_LINE_5,
                BmpFieldDefinition.DISPLAY_LINE_6,
                BmpFieldDefinition.DISPLAY_LINE_7,
                BmpFieldDefinition.DISPLAY_LINE_8,
            )

        lines.take(8).forEachIndexed { index, text ->
            if (text.isNotEmpty()) {
                fields[lineFields[index].fieldId] = text.toByteArray(Charsets.ISO_8859_1)
            }
        }

        return ZvtApdu.create(ZvtCommand.DISPLAY_TEXT, fields)
    }
}
