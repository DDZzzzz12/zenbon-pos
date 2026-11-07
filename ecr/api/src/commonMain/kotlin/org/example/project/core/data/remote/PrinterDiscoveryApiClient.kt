/**
 * API client interface for IPP printer discovery operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.printer.PrinterDto

interface PrinterDiscoveryApiClient {
    suspend fun discoverPrinters(): List<PrinterDto.DiscoveredPrinterDto>

    suspend fun getPrinterStatus(uri: String): PrinterDto.DiscoveredPrinterDto

    suspend fun getSupportedFormats(uri: String): List<String>

    suspend fun printPdf(
        pdfBytes: ByteArray,
        printerLocationId: Long,
    ): PrinterDto.PrintResponseDto

    suspend fun testPrint(printerLocationId: Long): PrinterDto.PrintResponseDto
}
