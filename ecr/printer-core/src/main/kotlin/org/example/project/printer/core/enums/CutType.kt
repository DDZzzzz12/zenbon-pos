/**
 * Type-safe ESC/POS paper cut enum.
 *
 * Encapsulates the GS V command for paper cutting.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Paper cut modes for ESC/POS thermal printers.
 *
 * @property bytes ESC/POS byte sequence to perform this cut
 */
enum class CutType(
    val bytes: ByteArray,
) {
    /** Full cut — completely separates the paper. */
    FULL(byteArrayOf(0x1D, 0x56, 0x42, 0x00)),

    /** Partial cut — leaves a small connection for easy tear. */
    PARTIAL(byteArrayOf(0x1D, 0x56, 0x01)),
}
