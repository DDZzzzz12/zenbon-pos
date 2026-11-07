/**
 * Type-safe ESC/POS font size enum.
 *
 * Encapsulates the GS ! n command (0x1D 0x21 n)
 * for controlling character height and width scaling.
 * Each entry also carries the effective line width in characters.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Font size modes for ESC/POS thermal printers.
 *
 * @property bytes ESC/POS byte sequence to set this font size
 * @property lineWidth Number of characters that fit on one line at this size
 */
enum class FontSize(
    val bytes: ByteArray,
    val lineWidth: Int,
) {
    /** Normal size — 1x height, 1x width (48 chars/line). */
    NORMAL(byteArrayOf(0x1D, 0x21, 0x00), 48),

    /** Double height only — 1x width, 2x height (48 chars/line). */
    DOUBLE_HEIGHT(byteArrayOf(0x1D, 0x21, 0x10), 48),

    /** Double width only — 2x width, 1x height (24 chars/line). */
    DOUBLE_WIDTH(byteArrayOf(0x1D, 0x21, 0x01), 24),

    /** Double height and width — 2x both (24 chars/line). */
    DOUBLE(byteArrayOf(0x1D, 0x21, 0x11), 24),

    /** Triple height and width — 3x both (16 chars/line). */
    TRIPLE(byteArrayOf(0x1D, 0x21, 0x22), 16),
}
