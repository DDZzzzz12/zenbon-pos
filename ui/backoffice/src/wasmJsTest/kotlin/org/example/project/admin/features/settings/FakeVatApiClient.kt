package org.example.project.admin.features.settings

import org.example.project.core.data.remote.VatApiClient
import org.example.project.core.domain.dto.setup.VatDto

/**
 * The three rows `VatRepositoryImpl.setStandardGermanRates()` seeds. The VAT-mapping section only
 * reads rates — it never writes one — so the mutating members throw rather than pretend.
 */
class FakeVatApiClient(
    private val rates: List<VatDto.VatResponseDto> =
        listOf(
            VatDto.VatResponseDto(vatId = "A", vatClass = "STANDARD_19", vatRate = 0.19, vatPercentage = "19.00%"),
            VatDto.VatResponseDto(vatId = "B", vatClass = "REDUCED_7", vatRate = 0.07, vatPercentage = "7.00%"),
            VatDto.VatResponseDto(vatId = "C", vatClass = "ZERO", vatRate = 0.0, vatPercentage = "0.00%"),
        ),
    private val onLoad: (() -> Unit)? = null,
) : VatApiClient {
    override suspend fun getAllVatRates(): List<VatDto.VatResponseDto> {
        onLoad?.invoke()
        return rates
    }

    override suspend fun createVatRate(request: VatDto.CreateVatRequestDto) = throw NotImplementedError()

    override suspend fun updateVatRate(
        vatId: String,
        request: VatDto.UpdateVatRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deleteVatRate(vatId: String) = throw NotImplementedError()
}
