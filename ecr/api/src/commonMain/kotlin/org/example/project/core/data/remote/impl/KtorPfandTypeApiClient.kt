package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.PfandTypeApiClient
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Throws-style Ktor implementation of [PfandTypeApiClient].
 */
class KtorPfandTypeApiClient(
    private val httpCall: HttpCall,
) : PfandTypeApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/pfand-types"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(includeInactive: Boolean): List<PfandTypeDto.PfandTypeResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                if (includeInactive) parameter("includeInactive", true)
            }
        }
}
