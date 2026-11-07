package org.example.project.admin.features.kassenbuch

import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto

/**
 * Records what the book-management ViewModel asks of the fiscal-configuration API.
 *
 * Only the cash-book transfer-target and Beleg-age pairs are implemented; every other section throws, so a test
 * that accidentally reaches one fails loudly instead of quietly passing.
 *
 * [updateRequests] is what proves the "off vs unset" distinction survives to the wire: an explicit
 * off records a request whose `cashBookId` is `null`, and a refused save records nothing at all.
 */
class FakeFiscalConfigurationApiClient(
    /** The stored target. `null` is "off" — the shop's default, not an absent value. */
    private var storedTarget: Long? = null,
    /** The shop's Kassenbuch Beleg-age limit. */
    var belegAgeDays: Int = 31,
) : FiscalConfigurationApiClient {
    val updateRequests: MutableList<ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto> = mutableListOf()

    override suspend fun getCashBookTransferTarget(shopId: Long) =
        ShopConfigurationDto.CashBookTransferTargetResponseDto(shopId = shopId, cashBookId = storedTarget)

    override suspend fun updateCashBookTransferTarget(
        shopId: Long,
        request: ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto,
    ): ShopConfigurationDto.CashBookTransferTargetResponseDto {
        updateRequests += request
        storedTarget = request.cashBookId
        return ShopConfigurationDto.CashBookTransferTargetResponseDto(shopId = shopId, cashBookId = request.cashBookId)
    }

    val belegAgeRequests: MutableList<ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto> = mutableListOf()

    override suspend fun getKassenbuchBelegAge(shopId: Long) = ShopConfigurationDto.KassenbuchBelegAgeResponseDto(shopId = shopId, maxAgeDays = belegAgeDays)

    override suspend fun updateKassenbuchBelegAge(
        shopId: Long,
        request: ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto,
    ): ShopConfigurationDto.KassenbuchBelegAgeResponseDto {
        belegAgeRequests += request
        belegAgeDays = request.maxAgeDays
        return ShopConfigurationDto.KassenbuchBelegAgeResponseDto(shopId = shopId, maxAgeDays = request.maxAgeDays)
    }

    override suspend fun getKassenMeldungConfig(shopId: Long) = throw NotImplementedError()

    override suspend fun downloadKassenMeldungPdf(shopId: Long) = throw NotImplementedError()
}
