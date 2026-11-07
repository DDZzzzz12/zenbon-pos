package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.onDeviceCredentialOnly

class KtorTerminalApiClient(
    private val httpCall: HttpCall,
) : TerminalApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/terminals"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun registerTerminal(request: TerminalDto.RegisterTerminalRequestDto): TerminalDto.TerminalResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/register")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getCurrentTerminal(): TerminalDto.TerminalResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/me")) }

    /** On the device token alone — see [onDeviceCredentialOnly] for why a person's bearer must not ride along. */
    override suspend fun getCurrentTerminalByDevice(): TerminalDto.TerminalResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/me-by-device")) { onDeviceCredentialOnly() } }

    override suspend fun openStationSession(): TerminalDto.StationSessionResponseDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/session")) }

    override suspend fun getAllTerminals(): List<TerminalDto.TerminalSummaryDto> = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun deactivateTerminal(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun updateTerminal(
        id: Long,
        request: TerminalDto.UpdateTerminalRequestDto,
    ): TerminalDto.TerminalResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun reactivateTerminal(id: Long): TerminalDto.TerminalSummaryDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/$id/reactivate")) }

    override suspend fun registerTerminalTse(id: Long): TerminalDto.TerminalSummaryDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/$id/tse-register")) }

    override suspend fun deregisterTerminalTse(id: Long): TerminalDto.TerminalSummaryDto =
        httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/$id/tse-deregister")) }

    override suspend fun syncTseStatus(): List<TerminalDto.TerminalSummaryDto> = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/sync-tse")) }

    override suspend fun pairDevice(request: TerminalDto.PairDeviceRequestDto): TerminalDto.PairDeviceResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/pair")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createTerminal(request: TerminalDto.CreateTerminalRequestDto): TerminalDto.TerminalResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun generatePairingCode(
        id: Long,
        registerTseClient: Boolean,
    ): TerminalDto.PairingCodeResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/$id/pairing-code")) {
                contentType(ContentType.Application.Json)
                setBody(TerminalDto.PairingCodeRequestDto(registerTseClient = registerTseClient))
            }
        }

    override suspend fun getTsePairingStatus(): TerminalDto.TsePairingStatusDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/tse-pairing-status")) }

    override suspend fun revokeBinding(id: Long): TerminalDto.TerminalSummaryDto = httpCall.httpClient.apiCall { post(url("$BASE_ENDPOINT/$id/revoke")) }
}
