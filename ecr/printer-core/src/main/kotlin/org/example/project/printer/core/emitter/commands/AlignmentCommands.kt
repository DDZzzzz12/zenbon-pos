/**
 * ESC/POS text alignment commands.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter.commands

import org.example.project.printer.core.enums.Alignment

/**
 * Alignment commands for horizontal text positioning.
 */
object AlignmentCommands {
    /**
     * Set text alignment using the type-safe [Alignment] enum.
     *
     * @param alignment Desired text alignment
     * @return ESC/POS byte sequence
     */
    fun setAlignment(alignment: Alignment): ByteArray = alignment.bytes
}
