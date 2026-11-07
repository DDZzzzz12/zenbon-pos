/**
 * USB printer implementation using javax.print API.
 *
 * Handles printing to USB-connected thermal printers using Java's standard
 * print service. Throws on failure (Stage C: Result envelope removed).
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.data.service

import org.example.project.printer.core.domain.service.PrintServiceInterface
import javax.print.DocFlavor
import javax.print.DocPrintJob
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc

/**
 * USB printer service implementation.
 *
 * Uses Java's javax.print API to send ESC/POS byte data
 * to USB thermal printers. Looks up printers by name.
 */
class UsbPrintServiceImpl : PrintServiceInterface {
    override suspend fun print(
        data: ByteArray,
        printerIdentifier: String,
    ): String {
        val printService =
            PrintServiceLookup
                .lookupPrintServices(null, null)
                .firstOrNull { it.name.equals(printerIdentifier, ignoreCase = true) }
                ?: error("USB printer not found: $printerIdentifier")

        val job: DocPrintJob = printService.createPrintJob()
        val doc = SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null)
        job.print(doc, null)

        return "Print job sent successfully to USB printer: $printerIdentifier"
    }
}
