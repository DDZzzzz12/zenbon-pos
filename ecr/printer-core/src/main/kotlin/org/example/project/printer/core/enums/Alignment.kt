/**
 * Type-safe ESC/POS text alignment enum.
 *
 * Encapsulates the ESC a n command (0x1B 0x61 n)
 * for controlling horizontal text alignment on thermal printers.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Text alignment modes for ESC/POS thermal printers.
 *
 * @property bytes ESC/POS byte sequence to set this alignment
 */
enum class Alignment(
    val bytes: ByteArray,
) {
    /** Align text to the left edge. */
    LEFT(byteArrayOf(0x1B, 0x61, 0x00)),

    /** Align text to the center. */
    CENTER(byteArrayOf(0x1B, 0x61, 0x01)),

    /** Align text to the right edge. */
    RIGHT(byteArrayOf(0x1B, 0x61, 0x02)),
}
