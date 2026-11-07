/**
 * API client interface for VAT operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.VatDto

interface VatApiClient {
    suspend fun getAllVatRates(): List<VatDto.VatResponseDto>

    suspend fun createVatRate(request: VatDto.CreateVatRequestDto): VatDto.VatResponseDto

    suspend fun updateVatRate(
        vatId: String,
        request: VatDto.UpdateVatRequestDto,
    ): VatDto.VatResponseDto

    suspend fun deleteVatRate(vatId: String)
}
