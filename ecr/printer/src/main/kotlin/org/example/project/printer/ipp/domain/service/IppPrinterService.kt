/**
 * IPP printer service interface for printer discovery, status checks, and IPP printing.
 *
 * Throws on failure (Stage C: Result envelope removed).
 *
 * @since 1.0.0
 */
package org.example.project.printer.ipp.domain.service

import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.printer.ipp.domain.model.DiscoveredPrinter

interface IppPrinterService {
    suspend fun discoverPrinters(): List<DiscoveredPrinter>

    suspend fun getPrinterStatus(printerUri: String): DiscoveredPrinter

    suspend fun printViaIpp(
        data: ByteArray,
        printerUri: String,
    ): String

    suspend fun printViaIpp(
        data: ByteArray,
        printerUri: String,
        documentFormat: IppDocumentFormat,
    ): String

    suspend fun getSupportedFormats(printerUri: String): List<String>
}
