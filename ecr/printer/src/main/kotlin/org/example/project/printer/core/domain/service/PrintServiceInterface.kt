/**
 * Base interface for all printer implementations.
 *
 * Defines the contract for sending data to thermal printers.
 * Implementations exist for USB (javax.print), Network (TCP socket),
 * and IPP (Internet Printing Protocol) printers.
 *
 * Throws on hardware/transport failure (Stage C: Result envelope removed).
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.domain.service

interface PrintServiceInterface {
    /**
     * Send raw byte data to a printer.
     *
     * The format of printerIdentifier depends on the implementation:
     * - USB: Printer name (e.g., "EPSON TM-T20")
     * - Network: IP:Port format (e.g., "192.168.1.100:9100")
     * - IPP: IPP URI (e.g., "ipp://192.168.1.100/ipp/print")
     *
     * @param data ESC/POS byte commands to send to printer
     * @param printerIdentifier Printer name (USB), IP:Port (Network), or URI (IPP)
     * @return success message
     */
    suspend fun print(
        data: ByteArray,
        printerIdentifier: String,
    ): String
}
