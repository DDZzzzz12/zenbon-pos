/**
 * Type-safe ESC/POS barcode type enum.
 *
 * Encapsulates the GS k command (0x1D 0x6B)
 * for selecting barcode symbology.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.enums

/**
 * Barcode symbology types for ESC/POS thermal printers.
 *
 * @property bytes ESC/POS byte sequence to select this barcode type
 */
enum class BarcodeType(
    val bytes: ByteArray,
) {
    /** CODE39 barcode — alphanumeric, variable length. */
    CODE39(byteArrayOf(0x1D, 0x6B, 0x04)),

    /** EAN-13 barcode — 12 data digits + 1 check digit. */
    EAN13(byteArrayOf(0x1D, 0x6B, 0x02)),
}
