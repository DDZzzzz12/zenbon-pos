package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.ChangelogApiClient
import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorChangelogApiClient(
    private val httpCall: HttpCall,
) : ChangelogApiClient {
    companion object {
        private const val CHANGELOG_ENDPOINT = "api/v1/changelog"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(
        page: Int,
        size: Int,
    ): ChangelogDto.PaginatedChangelogResponseDto =
        httpCall.httpClient.apiCall {
            get(url(CHANGELOG_ENDPOINT)) {
                parameter("page", page)
                parameter("size", size)
            }
        }
}
