/**
 * Ktor implementation of EcrConfigurationApiClient.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorEcrConfigurationApiClient(
    private val httpCall: HttpCall,
) : EcrConfigurationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getVoucherConfiguration(shopId: Long): ShopConfigurationDto.VoucherConfigResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/voucher-config")) }

    override suspend fun updateVoucherConfiguration(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVoucherConfigRequestDto,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            put(url("$BASE_ENDPOINT/$shopId/voucher-config")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    override suspend fun getVatMapping(shopId: Long): ShopConfigurationDto.VatMappingResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/vat-mapping")) }

    override suspend fun updateVatMapping(
        shopId: Long,
        request: ShopConfigurationDto.UpdateVatMappingRequestDto,
    ): ShopConfigurationDto.VatMappingResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/vat-mapping")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getPrintSettings(shopId: Long): ShopConfigurationDto.PrintSettingsResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/print-settings")) }

    override suspend fun updatePrintSettings(
        shopId: Long,
        request: ShopConfigurationDto.UpdatePrintSettingsRequestDto,
    ): ShopConfigurationDto.PrintSettingsResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/print-settings")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getOrderRules(shopId: Long): ShopConfigurationDto.OrderRulesResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/order-rules")) }

    override suspend fun updateOrderRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateOrderRulesRequestDto,
    ): ShopConfigurationDto.OrderRulesResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/order-rules")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getBillingRules(shopId: Long): ShopConfigurationDto.BillingRulesResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/billing-rules")) }

    override suspend fun updateBillingRules(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBillingRulesRequestDto,
    ): ShopConfigurationDto.BillingRulesResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/billing-rules")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
