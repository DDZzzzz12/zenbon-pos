/**
 * Ktor implementation of FiscalConfigurationApiClient.
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
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorFiscalConfigurationApiClient(
    private val httpCall: HttpCall,
) : FiscalConfigurationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getCashBookTransferTarget(shopId: Long): ShopConfigurationDto.CashBookTransferTargetResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/cash-book-transfer-target")) }

    override suspend fun updateCashBookTransferTarget(
        shopId: Long,
        request: ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto,
    ): ShopConfigurationDto.CashBookTransferTargetResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/cash-book-transfer-target")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getKassenbuchBelegAge(shopId: Long): ShopConfigurationDto.KassenbuchBelegAgeResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/kassenbuch-beleg-age")) }

    override suspend fun updateKassenbuchBelegAge(
        shopId: Long,
        request: ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto,
    ): ShopConfigurationDto.KassenbuchBelegAgeResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/kassenbuch-beleg-age")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getKassenMeldungConfig(shopId: Long): ShopConfigurationDto.KassenMeldungResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/kassen-meldung")) }

    override suspend fun downloadKassenMeldungPdf(shopId: Long): ByteArray =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/kassen-meldung/pdf")) }
}
