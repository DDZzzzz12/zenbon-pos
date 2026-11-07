/**
 * API client interface for printer location operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.printer.PrinterDto

interface PrinterLocationApiClient {
    suspend fun getAllPrinterLocations(includeInactive: Boolean = false): List<PrinterDto.PrinterLocationResponseDto>

    suspend fun createPrinterLocation(request: PrinterDto.CreatePrinterLocationRequestDto): PrinterDto.PrinterLocationResponseDto

    suspend fun updatePrinterLocation(
        id: Long,
        request: PrinterDto.UpdatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto

    suspend fun deactivatePrinterLocation(id: Long)

    suspend fun activatePrinterLocation(id: Long)
}
