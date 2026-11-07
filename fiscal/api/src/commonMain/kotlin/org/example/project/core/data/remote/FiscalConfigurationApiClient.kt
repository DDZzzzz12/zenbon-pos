/**
 * API client interface for the fiscal-owned shop configuration: the cash-book transfer target, the
 * Kassenbuch Beleg age and the Kassenmeldung.
 *
 * Split out of `ShopConfigurationApiClient` (Stage B2-5, Task C1); every method keeps its endpoint.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.ShopConfigurationDto

interface FiscalConfigurationApiClient {
    /**
     * The cash book each Z-Bon moves the day's Tageseinnahme into, or `cashBookId = null` when the
     * automatic Geldtransit is off — a valid configuration, and the server's default.
     */
    suspend fun getCashBookTransferTarget(shopId: Long): ShopConfigurationDto.CashBookTransferTargetResponseDto

    /** Pass `cashBookId = null` to switch the automatic transfer off; the field is never omitted. */
    suspend fun updateCashBookTransferTarget(
        shopId: Long,
        request: ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto,
    ): ShopConfigurationDto.CashBookTransferTargetResponseDto

    /** How many days back a new Kassenbuch entry's Beleg date may lie (default 31). */
    suspend fun getKassenbuchBelegAge(shopId: Long): ShopConfigurationDto.KassenbuchBelegAgeResponseDto

    suspend fun updateKassenbuchBelegAge(
        shopId: Long,
        request: ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto,
    ): ShopConfigurationDto.KassenbuchBelegAgeResponseDto

    suspend fun getKassenMeldungConfig(shopId: Long): ShopConfigurationDto.KassenMeldungResponseDto

    suspend fun downloadKassenMeldungPdf(shopId: Long): ByteArray
}
