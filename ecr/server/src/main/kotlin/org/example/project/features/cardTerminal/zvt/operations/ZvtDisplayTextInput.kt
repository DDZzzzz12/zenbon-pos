/**
 * ZVT Display Text with Input (06 E2) operation builder.
 *
 * Displays text on the payment terminal screen and prompts the customer
 * to enter numeric input. The terminal returns the entered digits.
 *
 * Reference: PA00P015 Chapter 2, command 06 E2.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.BmpFieldDefinition
import org.example.project.features.cardTerminal.zvt.protocol.ZvtApdu
import org.example.project.features.cardTerminal.zvt.protocol.ZvtCommand

/**
 * Builds the Display Text with Input (06 E2) command APDU.
 *
 * Uses BMP fields:
 * - F1-F8: Text lines 1-8 (LL-Var)
 * - E0: Display duration / timeout in seconds (1 byte)
 * - E1: Number of beeps (1 byte)
 * - E2: Minimum input length (1 byte)
 * - E3: Maximum input length (1 byte)
 * - E4: Echo character byte (1 byte, e.g., 0x2A = '*')
 */
object ZvtDisplayTextInput {
    /**
     * Builds a Display Text with Input APDU.
     *
     * @param lines List of text lines to display (max 8)
     * @param timeoutSeconds Input timeout in seconds (0 = no timeout)
     * @param beeps Number of beeps (default: 0)
     * @param minLength Minimum number of digits to accept (default: 1)
     * @param maxLength Maximum number of digits to accept (default: 16)
     * @param echoChar Character to echo for each digit (default: '*' = 0x2A)
     * @return The Display Text with Input command APDU
     */
    fun buildApdu(
        lines: List<String>,
        timeoutSeconds: Int = 30,
        beeps: Int = 0,
        minLength: Int = 1,
        maxLength: Int = 16,
        echoChar: Byte = 0x2A, // '*'
    ): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()

        // Timeout / display duration (BMP E0)
        fields[BmpFieldDefinition.DISPLAY_DURATION.fieldId] = byteArrayOf(timeoutSeconds.toByte())

        // Beeps (BMP E1)
        if (beeps > 0) {
            fields[BmpFieldDefinition.BEEPS.fieldId] = byteArrayOf(beeps.toByte())
        }

        // Input constraints (BMP E2, E3, E4)
        fields[BmpFieldDefinition.INPUT_MIN_LENGTH.fieldId] = byteArrayOf(minLength.toByte())
        fields[BmpFieldDefinition.INPUT_MAX_LENGTH.fieldId] = byteArrayOf(maxLength.toByte())
        fields[BmpFieldDefinition.INPUT_ECHO_CHAR.fieldId] = byteArrayOf(echoChar)

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

        return ZvtApdu.create(ZvtCommand.DISPLAY_TEXT_INPUT, fields)
    }
}
