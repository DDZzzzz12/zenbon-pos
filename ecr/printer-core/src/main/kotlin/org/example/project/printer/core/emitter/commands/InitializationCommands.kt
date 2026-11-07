/**
 * ESC/POS initialization and code page selection commands.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

/**
 * Printer initialization commands.
 */
object InitializationCommands {
    /** Reset printer to default state — clears buffers and settings. */
    val RESET = byteArrayOf(0x1B, 0x40)

    /**
     * Select character code page.
     *
     * ESC t n — selects code table n.
     * Common values: 0 = CP437, 2 = CP850, 19 = CP858.
     *
     * @param page Code page number (0–255)
     * @return ESC/POS byte sequence
     */
    fun selectCodePage(page: Int): ByteArray = byteArrayOf(0x1B, 0x74, (page and 0xFF).toByte())
}
