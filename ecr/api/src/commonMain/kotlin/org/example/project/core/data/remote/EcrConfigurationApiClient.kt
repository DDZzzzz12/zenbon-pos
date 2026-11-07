/**
 * API client interface for the ecr-owned shop configuration: voucher mode, VAT mapping, print
 * settings, order rules and billing rules.
 *
 * Split out of `ShopConfigurationApiClient` (Stage B2-5, Task C1); every method keeps its endpoint.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.ShopConfigurationDto

interface EcrConfigurationApiClient {
    suspend fun getVoucherConfiguration(shopId: Long): ShopConfigurationDto.VoucherConfigResponseDto

    suspend fun updateVoucherConfiguration(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVoucherConfigRequestDto,
    )

    suspend fun getVatMapping(shopId: Long): ShopConfigurationDto.VatMappingResponseDto

    suspend fun updateVatMapping(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVatMappingRequestDto,
    ): ShopConfigurationDto.VatMappingResponseDto

    suspend fun getPrintSettings(shopId: Long): ShopConfigurationDto.PrintSettingsResponseDto

    suspend fun updatePrintSettings(
        shopId: Long,
        request: ShopConfigurationDto.UpdatePrintSettingsRequestDto,
    ): ShopConfigurationDto.PrintSettingsResponseDto

    suspend fun getOrderRules(shopId: Long): ShopConfigurationDto.OrderRulesResponseDto

    suspend fun updateOrderRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateOrderRulesRequestDto,
    ): ShopConfigurationDto.OrderRulesResponseDto

    suspend fun getBillingRules(shopId: Long): ShopConfigurationDto.BillingRulesResponseDto

    suspend fun updateBillingRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBillingRulesRequestDto,
    ): ShopConfigurationDto.BillingRulesResponseDto
}
