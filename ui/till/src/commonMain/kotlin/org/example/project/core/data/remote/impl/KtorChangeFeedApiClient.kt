package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorChangeFeedApiClient(
    private val httpCall: HttpCall,
) : ChangeFeedApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/changes"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getChanges(
        since: Long,
        limit: Int,
    ): ChangeFeedDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                parameter("since", since)
                parameter("limit", limit)
            }
        }
}
