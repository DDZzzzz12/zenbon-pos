/**
 * Type-safe ESC/POS cash drawer pin enum.
 *
 * Encapsulates the ESC p command (0x1B 0x70)
 * for opening a cash drawer connected to pin 2 or pin 5.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Cash drawer pin selection for ESC/POS thermal printers.
 *
 * @property bytes ESC/POS byte sequence to pulse this pin
 */
enum class CashDrawerPin(
    val bytes: ByteArray,
) {
    /** Pin 2 — standard pulse duration: 25ms on, 500ms off. */
    PIN_2(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())),

    /** Pin 5 — standard pulse duration: 25ms on, 500ms off. */
    PIN_5(byteArrayOf(0x1B, 0x70, 0x01, 0x19, 0xFA.toByte())),
}
