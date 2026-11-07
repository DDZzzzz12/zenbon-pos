/**
 * ESC/POS line feed and vertical spacing commands.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

/**
 * Line feed commands for vertical paper movement.
 */
object LineFeedCommands {
    /** Single line feed — advance to next line (0x0A). */
    val LINE_FEED = byteArrayOf(0x0A)

    /** Double line feed — skip one line. */
    val DOUBLE_LINE_FEED = byteArrayOf(0x0A, 0x0A)

    /**
     * Feed a specific number of lines.
     *
     * @param count Number of line feeds (1–255)
     * @return Byte array containing [count] line feed characters
     */
    fun feedLines(count: Int): ByteArray = ByteArray(count.coerceIn(1, 255)) { 0x0A }
}
